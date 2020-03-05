package com.nvandervoort.mathathon

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.net.DatagramPacket


/**
 * Utility - contains useful classes and functions
 * as well as program-wide constants
 *
 * Created by nathanvandervoort on 2/20/18.
 */

object Headers {
    const val NEW_GAME = "NEW_GAME"
    const val START_GAME = "START_GAME"
    const val LEAVE_GAME = "LEAVE_GAME"
    const val QUESTIONS = "QUESTIONS"
    const val ANSWER = "ANSWER"
    const val IS_HOSTING = "HOSTING"
    const val GAME_OVER = "GAME_OVER"
}

/** Starts a thread with the given closure and returns the thread */
fun startOnThread(runnable: () -> Unit) = Thread(Runnable(runnable)).apply { start() }

/**
 * @author Dídac Pérez Parera
 * @see <a href="this post">https://stackoverflow.com/questions/18676471/best-way-to-avoid-toast-accumulation-in-android</a>
 */
object SingleToast {

    private var mToast: Toast? = null

    fun show(context: Context, text: String, duration: Int=Toast.LENGTH_SHORT) {
        hide()
        mToast = Toast.makeText(context, text, duration)
        mToast!!.show()
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun hide() {
        mToast?.cancel()
    }
}

/*
 * Extensions
 */

fun DatagramPacket.dataAsString() = String(data, 0, length)

val Activity.privatePrefs: SharedPreferences
    get() = getPreferences(Context.MODE_PRIVATE)

fun Activity.showToast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, text, duration).show()
}

fun Activity.showToastOnUiThread(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    runOnUiThread { showToast(text, duration) }
}

fun Activity.hideSoftKeyboard() {
    if (currentFocus == null) return
    val imgr = getSystemService(
            Activity.INPUT_METHOD_SERVICE) as InputMethodManager
    imgr.hideSoftInputFromWindow(currentFocus.windowToken, 0)
}

val Activity.ANIM_TIME_SHORT: Long
    get() = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
