package com.ntt.skyway.examples.p2proom

import android.Manifest
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.ntt.skyway.core.SkyWayContext
import com.ntt.skyway.core.util.Logger
import com.ntt.skyway.examples.p2proom.databinding.ActivityMainBinding
import com.ntt.skyway.room.member.RoomMember
import com.ntt.skyway.room.p2p.P2PRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.*


class MainActivity : AppCompatActivity() {
//    private val authToken = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpYXQiOjE3NTYzNDQ5MDQsImp0aSI6IjI5NjE1Y2QyLTkwYTEtNDA5ZC04OTNlLTdjNTFlYzE4ZDk2YiIsImV4cCI6MTc1NjQzMTMwNCwic2NvcGUiOnsiYXBwIjp7ImlkIjoiNjNiZGI5MDEtNzQyOC00M2ZiLTk5YjEtZDE4MWIwYzY0MzEwIiwidHVybiI6dHJ1ZSwiYWN0aW9ucyI6WyJyZWFkIiwid3JpdGUiXSwiY2hhbm5lbHMiOlt7ImlkIjoiKiIsIm5hbWUiOiIqIiwiYWN0aW9ucyI6WyJ3cml0ZSIsInJlYWQiLCJjcmVhdGUiXSwibWVtYmVycyI6W3siaWQiOiIqIiwibmFtZSI6IioiLCJhY3Rpb25zIjpbIndyaXRlIl0sInB1YmxpY2F0aW9uIjp7ImFjdGlvbnMiOlsid3JpdGUiXX0sInN1YnNjcmlwdGlvbiI6eyJhY3Rpb25zIjpbIndyaXRlIl19fV0sInNmdSI6eyJlbmFibGVkIjp0cnVlfSwic2Z1Qm90cyI6W3siYWN0aW9ucyI6WyJ3cml0ZSJdLCJmb3J3YXJkaW5ncyI6W3siYWN0aW9ucyI6WyJ3cml0ZSJdfV19XX1dfX19.oXQrQTmkceRGwIfdL21aOEnEvCfb9kG6W2qLmDHEosU"
    private val authToken = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpYXQiOjE3NTY4ODAzOTYsImp0aSI6ImVjMmVhYTE0LTc5MmEtNGM2Zi1iNGI2LWVkOWY3NjE2ZGJlZCIsImV4cCI6MTc1Njk2Njc5Niwic2NvcGUiOnsiYXBwIjp7ImlkIjoiMzI2YmVlNWUtNDNkYy00MmUwLWJmMDQtMmU5ZDhiZTMyMmUyIiwidHVybiI6dHJ1ZSwiYWN0aW9ucyI6WyJyZWFkIiwid3JpdGUiXSwiY2hhbm5lbHMiOlt7ImlkIjoiKiIsIm5hbWUiOiIqIiwiYWN0aW9ucyI6WyJ3cml0ZSIsInJlYWQiLCJjcmVhdGUiXSwibWVtYmVycyI6W3siaWQiOiIqIiwibmFtZSI6IioiLCJhY3Rpb25zIjpbIndyaXRlIl0sInB1YmxpY2F0aW9uIjp7ImFjdGlvbnMiOlsid3JpdGUiXX0sInN1YnNjcmlwdGlvbiI6eyJhY3Rpb25zIjpbIndyaXRlIl19fV0sInNmdSI6eyJlbmFibGVkIjp0cnVlfSwic2Z1Qm90cyI6W3siYWN0aW9ucyI6WyJ3cml0ZSJdLCJmb3J3YXJkaW5ncyI6W3siYWN0aW9ucyI6WyJ3cml0ZSJdfV19XX1dfX19.6SKdSsE5UF3F92pBFg5tzcYPMhe4lVV9Jie6NvdAXQ4"
//    private val appId = "648b5770-fac7-4236-a539-f37c58a1205a"
//    private val secretKey = "GinwWHGegKoNo24owWhOImQuzlITV5uEoC1gy6CDoLg="
 //   static {
    //     System.loadLibrary("opencv_java4");
 //   }
 // 推論


    private lateinit var binding: ActivityMainBinding

    private val scope = CoroutineScope(Dispatchers.IO)
    private val tag = this.javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(this.binding.root)

        checkPermission()


        initUI()
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.CAMERA
            ) != PermissionChecker.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.RECORD_AUDIO
            ) != PermissionChecker.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                ),
                0
            )
        } else {
            setupSkyWayContext()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(grantResults.isNotEmpty()
            && grantResults[0] == PermissionChecker.PERMISSION_GRANTED
            && grantResults[1] == PermissionChecker.PERMISSION_GRANTED){
            setupSkyWayContext()
        } else {
            Log.e("App","permission denied")
        }

    }

    private fun setupSkyWayContext(){
        scope.launch(Dispatchers.Default) {
            val option = SkyWayContext.Options(
                authToken = authToken,
                logLevel = Logger.LogLevel.VERBOSE
            )
            val result =  SkyWayContext.setup(applicationContext, option)
            if (result) {
                Log.d("App", "Setup succeed")
                RoomManager.isSkywayContextSetup = true
            }

        }

    }

    private fun initUI() {
        binding.apply {

            btnJoinChannel.setOnClickListener {
                val memberInit: RoomMember.Init
                if(binding.memberName.text.toString().isEmpty()){
                    memberInit = RoomMember.Init(UUID.randomUUID().toString())
                } else {
                    memberInit = RoomMember.Init(binding.memberName.text.toString())
                }


                scope.launch(Dispatchers.Main) {
                    RoomManager.room = P2PRoom.findOrCreate(roomName.text.toString())
                    if(RoomManager.room == null) {
                        Toast.makeText(this@MainActivity,"Room findOrCreate failed",Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    Log.d(tag, "findOrCreate Room id: " + RoomManager.room?.id)
                    Toast.makeText(this@MainActivity,"Room findOrCreate OK",Toast.LENGTH_SHORT).show()

                    RoomManager.localPerson = RoomManager.room!!.join(memberInit)
                    if (RoomManager.localPerson != null) {
                        Log.d(tag, "localPerson: " + RoomManager.localPerson?.id)
                        startActivity(Intent(this@MainActivity, RoomDetailsActivity::class.java))
                    } else {
                        Toast.makeText(applicationContext, "Joined Failed", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }

        }
    }

}
