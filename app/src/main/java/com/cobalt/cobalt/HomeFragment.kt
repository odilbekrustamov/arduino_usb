package com.cobalt.cobalt

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.*
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cobalt.cobalt.databinding.FragmentHomeBinding
import com.cobalt.cobalt.model.ListItem
import com.cobalt.cobalt.util.*
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.*

class HomeFragment : Fragment(), ServiceConnection, SerialListener {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var deviceId = 0
    private  var portNum:Int = 0
    private  var baudRate:Int = 115200

    private var usbSerialPort: UsbSerialPort? = null
    private var service: SerialService? = null

    private var controlLines: ControlLines? = null

    private var connected = Connected.False
    private var initialStart = true
    private val hexEnabled = false
    private var pendingNewline = false
    private val newline: String = TextUtil.newline_crlf

    private lateinit var resString: SpannableStringBuilder

    private enum class Connected {
        False, Pending, True
    }

    private var broadcastReceiver: BroadcastReceiver? = null

    private val listItems: ArrayList<ListItem> = ArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        retainInstance = true
    }

    override fun onDestroy() {
        if (connected != Connected.False) disconnect()
        requireActivity().stopService(Intent(activity, SerialService::class.java))
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        service?.attach(this)
            ?: requireActivity().startService(
                Intent(
                    activity,
                    SerialService::class.java
                )
            )
    }

    override fun onStop() {
        if (service != null && !requireActivity().isChangingConfigurations) service!!.detach()
        super.onStop()
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        requireActivity().bindService(
            Intent(getActivity(), SerialService::class.java),
            this,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDetach() {
        try {
            requireActivity().unbindService(this)
        } catch (ignored: java.lang.Exception) {
        }
        super.onDetach()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        requireActivity().registerReceiver(
            broadcastReceiver,
            IntentFilter(Constants.INTENT_ACTION_GRANT_USB)
        )
        if (initialStart && service != null) {
            initialStart = false
            requireActivity().runOnUiThread { this.connect() }
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        service = (binder as SerialService.SerialBinder).service
        service!!.attach(this)
        if (initialStart && isResumed) {
            initialStart = false
            requireActivity().runOnUiThread { this.connect() }
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        service = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root

        binding.ivCar1.setOnClickListener { v: View? ->
            send(
               "201"
            )
        }

        binding.ivCar2.setOnClickListener {
            send(
                "202"
            )
        }
        binding.ivCar3.setOnClickListener { v: View? ->
            send(
                "203"
            )
        }

        binding.ivCar4.setOnClickListener {
            send(
                "204"
            )
        }
        binding.ivCar5.setOnClickListener { v: View? ->
            send(
                "205"
            )
        }

        binding.ivCar6.setOnClickListener {
            send(
                "206"
            )
        }
        resString = SpannableStringBuilder()
        controlLines = ControlLines()

        return root
    }

    private fun connect() {
        connect(null)
    }

    private fun connect(permissionGranted: Boolean?) {
        var device: UsbDevice? = null
        val usbManager = requireActivity().getSystemService(Context.USB_SERVICE) as UsbManager
        for (v in usbManager.deviceList.values) if (v.deviceId == deviceId) device = v
        if (device == null) {
            status("connection failed: device not found")
            return
        }
        var driver = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            driver = CustomProber.customProber.probeDevice(device)
        }
        if (driver == null) {
            status("connection failed: no driver for device")
            return
        }
        if (driver.ports.size < portNum) {
            status("connection failed: not enough ports at device")
            return
        }
        usbSerialPort = driver.ports[portNum]
        val usbConnection = usbManager.openDevice(driver.device)
        if (usbConnection == null && permissionGranted == null && !usbManager.hasPermission(driver.device)) {
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val usbPermissionIntent = PendingIntent.getBroadcast(
                activity,
                0,
                Intent(Constants.INTENT_ACTION_GRANT_USB),
                flags
            )
            usbManager.requestPermission(driver.device, usbPermissionIntent)
            return
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.device)) status("connection failed: permission denied") else status(
                "connection failed: open failed"
            )
            return
        }
        connected = Connected.Pending
        try {
            usbSerialPort!!.open(usbConnection)
            usbSerialPort!!.setParameters(
                baudRate,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            val socket = SerialSocket(requireActivity().applicationContext, usbConnection, usbSerialPort)
            service!!.connect(socket)
            onSerialConnect()
        } catch (e: java.lang.Exception) {
            onSerialConnectError(e)
        }
    }

    private fun disconnect() {
        connected = Connected.False
        service!!.disconnect()
        usbSerialPort = null
    }

    private fun send(str: String) {
        if (connected != Connected.True) {
            Toast.makeText(activity, "not connected", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val msg: String
            val data: ByteArray
            if (hexEnabled) {
                val sb = StringBuilder()
                TextUtil.toHexString(sb, TextUtil.fromHexString(str))
                TextUtil.toHexString(sb, newline.toByteArray())
                msg = sb.toString()
                data = TextUtil.fromHexString(msg)
            } else {
                msg = str
                data = (str + newline).toByteArray()
            }
            val spn = SpannableStringBuilder(
                """
                  $msg
                  
                  """.trimIndent()
            )
            spn.setSpan(
                ForegroundColorSpan(resources.getColor(R.color.colorSendText)),
                0,
                spn.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            service!!.write(data)
        } catch (e: SerialTimeoutException) {
            status("write timeout: " + e.message)
        } catch (e: java.lang.Exception) {
            onSerialIoError(e)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun receive(datas: ArrayDeque<ByteArray>) {
        val spn = SpannableStringBuilder()
        for (data in datas) {
            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data)).append('\n')
            } else {
                var msg = String(data)
                if (newline == TextUtil.newline_crlf && msg.length > 0) {
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf)
                    if (pendingNewline && msg[0] == '\n') {
                        if (spn.length >= 2) {
                            spn.delete(spn.length - 2, spn.length)
                        }
                    }
                    pendingNewline = msg[msg.length - 1] == '\r'
                }
                spn.append(TextUtil.toCaretString(msg, newline.length != 0))

                resString!!.append(TextUtil.toCaretString(msg, newline.length != 0))

               try {
                   if (resString!!.startsWith('#') && resString!!.endsWith('@')){

                       resString.delete(0, 1)
                       resString.delete(resString.length-1, resString.length)

                       val array = resString.split("END")

                       array.forEach {
                           Toast.makeText(requireContext(), it.substring(0, 3), Toast.LENGTH_SHORT).show()
                           if (it.substring(0, 3).toInt() == 101){
                               binding.tvCarSpeed.text = "${it.substring(3, it.length)} km/soat"
                           }else if (it.substring(0, 3).toInt() == 102){
                               binding.tvRPM.text = "${it.substring(3, it.length)} "
                           }else if (it.substring(0, 3).toInt() == 103){
                               binding.tvGasolinePercent.text = "${it.substring(3, it.length)} "
                           }else if (it.substring(0, 3).toInt() == 104){
                               binding.tvInsideTempNum.text = "${it.substring(3, it.length)} "
                           }else if (it.substring(0, 3).toInt() == 105){
                               binding.tvOutsideTempNum.text = "${it.substring(3, it.length)} "
                           }else if (it.substring(0, 3).toInt() == 201){
                               val n = it.substring(3, it.length)
                               if (n.toInt() == 0){
                                   binding.ivCar1.setImageResource(R.drawable.ic_circle)
                               }else{
                                   binding.ivCar1.setImageResource(R.drawable.ic_circle_outline_24)
                               }
                           }else if (it.substring(0, 3).toInt() == 202){
                               val n = it.substring(3, it.length)
                               if (n.toInt() == 0){
                                   binding.ivCar2.setImageResource(R.drawable.ic_circle)
                               }else{
                                   binding.ivCar2.setImageResource(R.drawable.ic_circle_outline_24)
                               }
                           }else if (it.substring(0, 3).toInt() == 203){
                               val n = it.substring(3, it.length)
                               if (n.toInt() == 0){
                                   binding.ivCar3.setImageResource(R.drawable.ic_circle)
                               }else{
                                   binding.ivCar3.setImageResource(R.drawable.ic_circle_outline_24)
                               }
                           }else if (it.substring(0, 3).toInt() == 204){
                               val n = it.substring(3, it.length)
                               if (n.toInt() == 0){
                                   binding.ivCar4.setImageResource(R.drawable.ic_circle)
                               }else{
                                   binding.ivCar4.setImageResource(R.drawable.ic_circle_outline_24)
                               }
                           }else if (it.substring(0, 3).toInt() == 205){
                               val n = it.substring(3, it.length)
                               if (n.toInt() == 0){
                                   binding.ivCar5.setImageResource(R.drawable.ic_circle)
                               }else{
                                   binding.ivCar5.setImageResource(R.drawable.ic_circle_outline_24)
                               }
                           }else if (it.substring(0, 3).toInt() == 206){
                               val n = it.substring(3, it.length)
                               if (n.toInt() == 0){
                                   binding.ivCar6.setImageResource(R.drawable.ic_circle)
                               }else{
                                   binding.ivCar6.setImageResource(R.drawable.ic_circle_outline_24)
                               }
                           }
                       }
                       resString.delete(0, resString.length)
                   }else if (!resString!!.startsWith('#')){
                       resString.delete(0, resString.length)
                   }
               }catch (e: Exception){
                   print(e.message)
               }
            }
        }
    }

    fun status(str: String) {
        val spn = SpannableStringBuilder(
            """
              $str
              
              """.trimIndent()
        )
        spn.setSpan(
            ForegroundColorSpan(resources.getColor(R.color.colorStatusText)),
            0,
            spn.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    override fun onSerialConnect() {
        status("connected")
        connected = Connected.True
    }

    override fun onSerialConnectError(e: Exception?) {
        status("connection failed: " + e!!.message)
        disconnect()
    }

    override fun onSerialRead(data: ByteArray?) {
        val datas = ArrayDeque<ByteArray>()
        datas.add(data)
        receive(datas)
    }

    override fun onSerialRead(datas: ArrayDeque<ByteArray?>?) {
        receive(datas as ArrayDeque<ByteArray>)
    }

    override fun onSerialIoError(e: Exception?) {
        status("connection lost: " + e!!.message)
        disconnect()
    }

    inner class ControlLines() {
        private val mainLooper: Handler
        private val runnable: Runnable

        init {
            mainLooper = Handler(Looper.getMainLooper())
            runnable =
                Runnable { this.run() }
        }

        private fun run() {
            if (connected != Connected.True) return
        }
    }

    fun refresh() {
        val usbManager = requireActivity().getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDefaultProber = UsbSerialProber.getDefaultProber()
        val usbCustomProber: UsbSerialProber = CustomProber.customProber
        listItems.clear()
        for (device in usbManager.deviceList.values) {
            var driver = usbDefaultProber.probeDevice(device)
            if (driver == null) {
                driver = usbCustomProber.probeDevice(device)
            }
            if (driver != null) {
                for (port in driver.ports.indices) listItems.add(
                    ListItem(
                        device,
                        port,
                        driver
                    )
                )
            } else {
                listItems.add(
                    ListItem(
                        device,
                        0,
                        null
                    )
                )
            }
        }

       try {
           deviceId = listItems[0].device.deviceId
           portNum = listItems[0].port

       }catch (e: Exception) {
       }
    }

}