package com.nvandervoort.mathathon

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.graphics.Color
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.content.res.ResourcesCompat
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import kotlinx.android.synthetic.main.activity_main.*
import java.net.*
import android.os.CountDownTimer
import android.widget.ArrayAdapter
import com.jaredrummler.android.device.DeviceName


/**
 * MainActivity.kt
 *
 * created by Nathan Vandervoort on 2/5/18
 * for CS176B W18 quarter project
 */

class MainActivity : AppCompatActivity() {

    private val socket: ClientUdpSocket = ClientUdpSocket.instance
    private lateinit var pastIps: MutableSet<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Only 4 digit ports allowed
        enter_port_num.addTextChangedListener(object: TextWatcher {
            override fun afterTextChanged(p0: Editable?) {}

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}

            override fun onTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {
                if (text?.length == 4) {
                    enter_port_num.setTextColor(
                            ResourcesCompat.getColor(resources, R.color.colorAccent, null))
                    status_text.text = getString(R.string.checking_port)
                    showConnecting(true)
                    checkIfAddrValid(enter_ip.text.toString(), text.toString().toInt())
                } else {
                    enter_port_num.setTextColor(
                            ResourcesCompat.getColor(resources, R.color.colorPrimary, null))
                    port_validity_icon.visibility = View.GONE
                    status_text.visibility = View.INVISIBLE
                    socket.cancelWait()
                    showConnecting(false)
                    join_game_button.isEnabled = false
                    join_game_button.setTextColor(Color.LTGRAY)
                }
            }
        })

        pastIps = privatePrefs.getStringSet(PAST_IPS, setOf()).toMutableSet()
        val adapter = ArrayAdapter<String>(this,
                android.R.layout.simple_dropdown_item_1line,
                pastIps.toList())
        enter_ip.setAdapter(adapter)

        join_game_button.setOnClickListener {
            startOnThread {
                socket.send("${Headers.NEW_GAME}-${DeviceName.getDeviceName()};")
                socket.waitForResponse(Headers.NEW_GAME)
            }.join()
            showWaiting(true)
            awaitQuestions()
        }

        cancel_button.setOnClickListener {
            enter_port_num.setText("")
            showWaiting(false)
            showConnecting(false)
            setJoinButtonClickability(false)
            cancel_button.visibility = View.GONE
            startOnThread {
                socket.send("${Headers.LEAVE_GAME};")
                socket.cancelWait()
            }
        }

    }

    override fun onResume() {
        super.onResume()
        setJoinButtonClickability(false)
        countdown.visibility = View.GONE
        enter_port_num.setText("")
    }

    /** Checks format of address and validity of port by contacting server */
    private fun checkIfAddrValid(ip: String, port: Int) {
        if (!ip.matches(Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}"))) {
            runOnUiThread {
                status_text.text = getString(R.string.invalid_ip)
                port_validity_icon.setImageResource(R.drawable.invalid_red)
                showConnecting(false)
                port_validity_icon.visibility = View.VISIBLE
                setJoinButtonClickability(false)
            }
            return
        }

        fun setPortValid(valid: Boolean) = runOnUiThread {
            status_text.text = getString(if (valid) R.string.port_valid_dialog else R.string.port_invalid_dialog)
            port_validity_icon.setImageResource(if (valid) R.drawable.valid_blue else R.drawable.invalid_red)
            setJoinButtonClickability(valid)
        }

        startOnThread {
            socket.changeTargetAddress(ip, port)
            socket.send("${Headers.IS_HOSTING};")
            try {
                if (socket.waitForResponse(Headers.IS_HOSTING, 4000).isNotEmpty())
                    setPortValid(true)
            } catch (ignored: SocketTimeoutException) {
                setPortValid(false)
            }

            runOnUiThread {
                showConnecting(false)
                port_validity_icon.visibility = View.VISIBLE
            }
        }
    }

    /** Waits for question data, confirm receipt */
    private fun awaitQuestions() {
        runOnUiThread { status_text.text = getString(R.string.waiting_for_players) }
        startOnThread {
            val pktData = socket.waitForResponse(Headers.QUESTIONS)
            if (pktData.isEmpty()) return@startOnThread
            val info = pktData.removePrefix(Headers.QUESTIONS).split('|')
            val numQuestions = info[0].toInt()
            val difficulty = info[1].toInt()
            val timeLimit = info[2].toInt()
            val questions = info[3]
            socket.send("${Headers.START_GAME};")
            try {
                if (socket.waitForResponse(Headers.START_GAME).isEmpty()) return@startOnThread
            } catch (ignored: SocketTimeoutException) {}
            countdownGameStart {
                startGame(numQuestions, difficulty, timeLimit, questions)
            }
        }
    }

    /** Moves to next activity where game automatically starts */
    private fun startGame(numQuestions: Int, difficulty: Int, timeLimit: Int, questions: String) {
        if (socket.targetIp != null) pastIps.add(socket.targetIp!!)
        val editor = privatePrefs.edit()
        editor.putStringSet(PAST_IPS, pastIps)
        editor.apply()

        val intent = Intent(this, GameActivity::class.java)
        intent.putExtra("numQuestions", numQuestions)
        intent.putExtra("difficulty", difficulty)
        intent.putExtra("timeLimit", timeLimit)
        intent.putExtra("questions", questions)
        startActivity(intent)
    }

    private fun countdownGameStart(finish: () -> Unit) = runOnUiThread {
        showWaiting(false)
        for (v in arrayOf(join_game_button, cancel_button)) v.visibility = View.GONE
        countdown.visibility = View.VISIBLE
        status_text.text = getString(R.string.game_ready)
        object : CountDownTimer((COUNTDOWN_LENGTH + 1) * 1000, 1000) {

            override fun onTick(millisUntilFinished: Long) {
                countdown.text = (millisUntilFinished/1000).toString()
            }

            override fun onFinish() {
                finish()
            }
        }.start()
    }

    private fun setJoinButtonClickability(clickable: Boolean) {
        join_game_button.visibility = View.VISIBLE
        join_game_button.setTextColor(if (clickable) Color.BLACK else Color.LTGRAY)
        join_game_button.isEnabled = clickable
    }

    private fun showWaiting(show: Boolean) {
        join_game_button.visibility = if (show) View.INVISIBLE else View.VISIBLE
        if (show) {
            cancel_button.visibility = View.VISIBLE
            hideSoftKeyboard()
        }

        waiting_spinkit.animate()
                .setDuration(ANIM_TIME_SHORT)
                .alpha(if (show) 1f else 0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        waiting_spinkit.visibility = if (show) View.VISIBLE else View.INVISIBLE
                    }
                })
    }

    private fun showConnecting(show: Boolean) {
        if (show) status_text.visibility = View.VISIBLE

        connecting_spinkit.animate()
                .setDuration(ANIM_TIME_SHORT)
                .alpha(if (show) 1f else 0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        connecting_spinkit.visibility = if (show) View.VISIBLE else View.INVISIBLE
                    }
                })
    }

    companion object {
        const val COUNTDOWN_LENGTH: Long = 5
        const val PAST_IPS = "past_ips"
    }

}
