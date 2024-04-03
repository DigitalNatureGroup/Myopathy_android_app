package com.digitalnature.myopathy_breath_v1

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.github.aachartmodel.aainfographics.aachartcreator.AAChartModel
import com.github.aachartmodel.aainfographics.aachartcreator.AAChartType
import com.github.aachartmodel.aainfographics.aachartcreator.AAChartView
import com.github.aachartmodel.aainfographics.aachartcreator.AASeriesElement
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.runBlocking
import quevedo.soares.leandro.blemadeeasy.BLE
import quevedo.soares.leandro.blemadeeasy.models.BLEDevice
import java.lang.System.currentTimeMillis
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Timer
import java.util.TimerTask

val UUID_READ="49535343-1e4d-4bd9-ba61-23c647249616"
val UUID_WRITE="49535343-8841-43f4-a8d4-ecbe34729bb3"
val scale_factor_period = 200 // 0 ~ 200 * 25 = 5000 ms
val scale_factor_depth = 4 // 0 ~ 4 * 25 = 100
val record_heartrate_maxlen : Int = 50
// val scale_factor_breath_time = 0.04 // 4 % * 25 = 100% of period


class MainActivity : AppCompatActivity() {



    private lateinit var dataClient : DataClient
    private lateinit var textView : TextView
    private lateinit var errorView : TextView
    private lateinit var sendButton : Button
    private lateinit var node_id : String
    private lateinit var handlerThread : HandlerThread
    private lateinit var breath_ChartView : AAChartView
    private var last_value_serial : Pair<Double? , Long> = Pair(null, currentTimeMillis())
    private var last_value_heartrate : Pair<Double , Long> = Pair(0.0, currentTimeMillis())
    private var permit_fine_loc: Boolean = false
    private var permit_blue_scan: Boolean = false
    private var our_device : BLEDevice? = null
    private var isLocked : Boolean = true

    private var record_heartrate_lst : MutableList<Pair<Double, Long>> = mutableListOf()

    
    private var BLE_conn_status : Int = 0
    // 0: disconn, 1: connecting, 2: connected
    private lateinit var ble : BLE

    private val timer_ui_refresh = Timer("UI_refresh_timer")
    private val timer_BLE_get = Timer("BLE_timer")

    private val textview_template = "last serial written: %s , %s \n last watch read: %s , %s "

    private val MBLadapter: BluetoothAdapter? by lazy(LazyThreadSafetyMode.NONE) {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    private val BluetoothAdapter.isDisabled: Boolean
        get() = !isEnabled


    private val ui_update_handler = Handler(Looper.getMainLooper()) { msg ->

        if (msg.what == 1) {

            //由于需要主线程显示UI，这里使用Handler通信
            updateUI()
        }

        true
    }



    @SuppressLint("VisibleForTests")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.d("-","setContentView Done")
        // Get permission


        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permit_fine_loc = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permit_blue_scan = shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN)
        }

        ble = BLE(componentActivity = this)

        start_listen_watch()

        textView = findViewById(R.id.DataShow)
        textView.setText(R.string.waiting)

        errorView = findViewById(R.id.ErrorStatusShow)
        errorView.setText(R.string.error_placeholder)

        breath_ChartView = findViewById<AAChartView>(R.id.aa_chart_view)
        // init scan
        /*timer_BLE_get.schedule(
            object :TimerTask(){
                override fun run() {
                    scanBLEDevice2Write()
                }

            },
            250L,
        )*/

        scan4device()

        timer_BLE_get.scheduleAtFixedRate(
            object :TimerTask(){
                override fun run() {
                    runBlocking {
                        write2thedevice()
                    }
                }
            },
            250L,
            5000L
        )

        // Update


        timer_ui_refresh.schedule(
            object : TimerTask() {
                override fun run() {
                    while (true) {

                        try {

                            Thread.sleep(10000);//线程暂停10秒，单位毫秒
                            val message = Message();
                            message.what=1;
                            ui_update_handler.sendMessage(message);//发送消息
                        } catch (e : InterruptedException ) {
                            e.printStackTrace();
                        }
                    }
                }
            },
            1000L,
        )

    }

    private fun updateUI(){
        val text = String.format(
            textview_template,
            "{%.2f}".format(last_value_serial.first),
            last_value_serial.second,
            last_value_heartrate.first,
            last_value_heartrate.second
        )
        Log.i("jz","UI updated")
        textView.setText(text)
        drawBreathChart()
    }

    fun update_lst(value_heartrate : Pair<Double , Long>){
        if(value_heartrate.first <= 10.0 ){ // you would surely die
            return
        }
        if(!record_heartrate_lst.isEmpty() && value_heartrate.second - record_heartrate_lst.last().second < 100 ){ // 100ms
            return
        }
        record_heartrate_lst.add(
            value_heartrate

        )
        if (record_heartrate_lst.size > record_heartrate_maxlen){
            record_heartrate_lst.removeAt(0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun scan4device(){
        ble.scanAsync(
            duration = 10000,

            /* This is optional, if you want to update your interface in realtime */
            onDiscover = { device ->
                //macAddress = "00:0C:BF:0A:78:4C",
                if (device.name != "HC-02"){
                    Log.i("jz","Not our device")
                }else{
                    our_device = device
                    Log.i("jz","!-- GOT our device! -- !")

                }
                // Update your UI with the newest found device, in real time
            },

            onFinish = { devices ->
                // Continue with your code handling all the devices found
            },
            onError = { errorCode ->
                // Show an Alert or UI with your preferred error message
                Log.e("jz","errorcode:{%d}".format(errorCode))
            }
        )
    }

    private fun showDialogToGetPermission() {
        Log.d("jz_permit","fine location permission")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Permisisons request")
            .setMessage("We need the location permission for some reason. " +
                    "You need to move on Settings to grant some permissions")

        builder.setPositiveButton("OK") { dialogInterface, i ->
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)   // 6
        }
        builder.setNegativeButton("Later") { dialogInterface, i ->
            // ignore
        }
        val dialog = builder.create()
        dialog.show()
    }

    private fun showDialogToGetBluetoothScanPermission() {
        Log.d("jz_permit","BLE permission")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Permisisons request")
            .setMessage("We need the Bluetooth scan permission for some reason. " +
                    "You need to move on Settings to grant some permissions")

        builder.setPositiveButton("OK") { dialogInterface, i ->
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)   // 6
        }
        builder.setNegativeButton("Later") { dialogInterface, i ->
            // ignore
        }
        val dialog = builder.create()
        dialog.show()
    }

    private fun getDiffArray_heartrate(inputList: List<Pair<Double,Long>>): Array<Array<Double>> {
        val res = Array(inputList.size){ arrayOf(0.0,0.0) }
        if (inputList.isEmpty()){
            return arrayOf(arrayOf(0.0,0.0))
        }
        val baseline = inputList.first().second
        for (i in inputList.indices){
            val pair = inputList[i]
            res[i][0] = (pair.second - baseline).toDouble() / 1000// time is X
            res[i][1] = pair.first// val is Y

        }
        return res
    }

    private fun drawBreathChart(){
        // unit: second
        val dummy = arrayOf(
            arrayOf(0.7, 6.9),
            arrayOf(0.95, 1.5),
            arrayOf(0.82, 1.5),
            arrayOf(0.252, 2.5),
            arrayOf(0.233, 1.3),
            arrayOf(0.139, 0.6)
        )
        val got = getDiffArray_heartrate(record_heartrate_lst)
        val aaChartModel = AAChartModel()
            .chartType(AAChartType.Scatter)
            .title("Breath")
            .subtitle("subtitle")
            .backgroundColor("#4b2b7f")
            .series(arrayOf(
                AASeriesElement()
                    .name("heartrate")
                    .data(
                        getDiffArray_heartrate(record_heartrate_lst).asList().toTypedArray()
                    ),

            )
            )

        breath_ChartView.aa_drawChartWithChartModel(aaChartModel)
    }

    fun start_listen_watch() {
        handlerThread = object : HandlerThread("BackgroundThread") {
            override fun onLooperPrepared() {
                val handler = Handler(looper)
                val runnable = object : Runnable {
                    override fun run() {
                        get_data_task()
                        handler.postDelayed(this,50)
                    }
                }
                handler.postDelayed(runnable,300)
            }
        }
        handlerThread.start()
        //FIXME

    }

    @SuppressLint("VisibleForTests")
    fun get_data_task(){
        this.dataClient = Wearable.getDataClient(this)
        //val dataItemTask = dataClient.getDataItems(Uri.parse("wear://%s/health".format(node_id))) //wear://<node_id>/<path>
        val dataItemTask = dataClient.getDataItems(Uri.parse("wear://*/health"))
        dataItemTask.addOnSuccessListener { dataItems ->
            for (dataItem in dataItems) {
                Log.i("phone-got-data","$dataItem")
                if (dataItem.uri.path == "/health") {
                    val myData = DataMapItem.fromDataItem(dataItem).dataMap.getDouble("heartrate")
                    // do something with myData
                    Log.i("phone-got-data","$myData")
                    this.last_value_heartrate = Pair(myData, currentTimeMillis())
                    this.update_lst(this.last_value_heartrate)

                    //textView.setText(String.format("Current Heartrate : %.1f",myData))

                }
            }
            dataItems.release()
        }

    }

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }


// It is important to keep in mind that every single one of the provided arguments of the function shown above, are optionals! Therefore, you can skip the ones that you don't need.



    // Stops scanning after 10 seconds.
    private val SCAN_PERIOD: Long = 10000

    @SuppressLint("MissingPermission")
    private suspend fun write2thedevice(): Boolean {
        if (our_device == null){ // in connection
            Log.d("jz","our device null")
            return false
        }
        ble.connect(our_device!!)?.let { connection ->
            // Continue with your code
            //val value = connection.read("49535343-ACA3-481C-91EC-D85E28A60318")
            val val2send : Double = last_value_heartrate.first
            connection.write(UUID_WRITE, "H%03d-".format((val2send* 10).toInt()))
            Log.i("jz","sent")
            last_value_serial = Pair(val2send,currentTimeMillis())
            connection.close()
            Log.i("jz","closed")
        }
        return true
    }


// It is important to keep in mind that every single one of the provided arguments of the function shown above, are optionals! Therefore, you can skip the ones that you don't need.





    override fun onDestroy() {


        handlerThread.quit()
        super.onDestroy()

    }




}