package com.nvandervoort.mathathon

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.os.Bundle
import android.os.CountDownTimer
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import kotlinx.android.synthetic.main.activity_game.*
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * GameActivity.kt
 *
 * created by Nathan Vandervoort on 2/8/18
 * for CS176B W18 quarter project
 */

class GameActivity : AppCompatActivity() {

    private val socket = ClientUdpSocket.instance

    private var questionInd: Int = -1
    private lateinit var questions: List<Question>
    private var numQuestions = 0
    private var currentPerfomance = PerformanceSnapshot()
    private lateinit var countDownTimer: CountDownTimer

    class Question(formattedQuestion: String) {
        val firstOperand: String; val secondOperand: String
        val operator: Char
        val answer: Int
        init {
            val parts = formattedQuestion.split(':')
            firstOperand = parts[0].trim()
            secondOperand = parts[1].trim()
            operator = parts[2][0]
            answer = parts[3].toInt()
        }
    }

    @Suppress("EqualsOrHashCode")
    class PerformanceSnapshot {
        var numCompleted = 0
        private var numAnswered = 0
        private var numCorrect = 0

        fun questionAnswered(correct: Boolean) {
            numCompleted++
            numAnswered++
            if (correct) numCorrect++
        }

        fun questionSkipped() {
            numCompleted++
        }

        fun packetFormat() = "${Headers.ANSWER}-$numCompleted-$numAnswered-$numCorrect;"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as PerformanceSnapshot

            if (numCompleted != other.numCompleted) return false
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        // get game info
        numQuestions = intent.getIntExtra("numQuestions", -1)
        val difficulty = intent.getIntExtra("difficulty", -1)  // todo: display this
        val timeLimit = intent.getIntExtra("timeLimit", -1)
        questions = intent.getStringExtra("questions").split(',')
                .map { Question(it) }

        title = "Game - ${if (difficulty == 1) "Easy" else "Hard"} Difficulty"

        game_progress_bar.max = numQuestions

        submit_button.setOnClickListener {
            if (answer.text.isEmpty()) return@setOnClickListener
            val ans = answer.text.toString().toInt()
            if (ans == questions[questionInd].answer)
                currentPerfomance.questionAnswered(true)
            else currentPerfomance.questionAnswered(false)
            updateAndAwaitConfirmation()
        }

        skip_button.setOnClickListener {
            currentPerfomance.questionSkipped()
            updateAndAwaitConfirmation()
        }

        nextQuestion()
        countDownTimer = object : CountDownTimer(timeLimit.toLong() * 1000, 1000) {

            @SuppressLint("SetTextI18n")
            override fun onTick(millisUntilFinished: Long) {
                val minutes = TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished)
                val seconds = TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) % 60
                time_left.text = "$minutes:${if (seconds < 10) "0" else ""}$seconds"
            }

            override fun onFinish() {
                time_left.text = getString(R.string.zero_time)
                endGame()
            }
        }

        countDownTimer.start()

    }

    /** Sends performance packet to server and wait for confirmation
     *  Drops packets with [DROP_RATE] and resends packet if no response for [RETRANSMIT_DELAY] ms */
    private fun updateAndAwaitConfirmation() {
        showWaiting(true)
        Log.d("GAME_UPDATE", "perf packet: ${currentPerfomance.packetFormat()}")

        startOnThread {
            // wait if drop rate low enough or it is the last question
            val waitForConfirmation = DROP_RATE < CONFIRMATION_THRESHOLD
                    || questionInd == numQuestions - 1
            var confirmed = false
            while (!confirmed) {
                socket.send(currentPerfomance.packetFormat(), DROP_RATE)
                try {
                    confirmed = !waitForConfirmation || socket.waitForResponse(Headers.ANSWER, RETRANSMIT_DELAY)
                            .split('-')[1]
                            .toInt() == currentPerfomance.numCompleted
                } catch (ignored: SocketTimeoutException) {}
            }
            nextQuestion()
        }
    }

    /** Updates question display */
    @SuppressLint("SetTextI18n")
    private fun nextQuestion() = runOnUiThread {
        questionInd++
        if (questionInd < numQuestions) {
            question_number.text = "Question ${questionInd + 1}/$numQuestions"
            val q = questions[questionInd]
            first_operand.text = q.firstOperand
            second_operand.text = q.secondOperand
            operator.text = when (q.operator) {
                '+' -> "\u002B"
                '-' -> "\u2212"
                '*' -> "\u00D7"
                '/' -> "\u00F7"
                else -> "?"
            }
            answer.setText("")
            game_progress_bar.progress = questionInd
        } else endGame()
        showWaiting(false)
    }

    private fun showWaiting(show: Boolean) {
        for (v in arrayOf(skip_button, submit_button)) v.visibility =
                if (show) View.INVISIBLE
                else View.VISIBLE

        awaiting_confirmation_spinkit.animate()
                .setDuration(ANIM_TIME_SHORT)
                .alpha(if (show) 1f else 0f)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        awaiting_confirmation_spinkit.visibility =
                                if (show) View.VISIBLE else View.INVISIBLE
                    }
                })
    }

    /** Hides question views, waits for result, then displays it */
    private fun endGame() {
        Log.d("GAME", "endGame()")
        countDownTimer.cancel()
        showWaiting(false)
        socket.cancelWait()
        if (questionInd == numQuestions) game_progress_bar.progress = numQuestions
        for (view in listOf(first_operand, second_operand, operator, answer))
            view.visibility = View.INVISIBLE
        skip_submit_linear_layout.visibility = View.GONE
        waiting_end_of_game.visibility = View.VISIBLE

        startOnThread {
            val won =  socket.waitForResponse(Headers.GAME_OVER).split('-')[1] == "w"
            runOnUiThread {
                waiting_end_of_game.text =
                        if (won) "Winner winner chicken dinner!"
                        else "Better luck next time"
            }
            socket.send("${Headers.GAME_OVER};")
            Thread.sleep(300)
            socket.send("${Headers.GAME_OVER};")  // sometimes the first one isn't timed right

        }
    }

    companion object {
        const val DROP_RATE = 29                // percent
        const val CONFIRMATION_THRESHOLD = 30   // percent
        const val RETRANSMIT_DELAY = 300        // ms
    }
}
