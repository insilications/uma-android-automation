package com.steve1316.uma_android_automation.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.Toast
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.R
import com.steve1316.uma_android_automation.bot.Game
//import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.*

/**
 * This Service will allow starting and stopping the automation workflow on a Thread based on the chosen preference settings.
 *
 * Source for being able to send custom Intents to BroadcastReceiver to notify users of bot state changes is from:
 * https://www.tutorialspoint.com/in-android-how-to-register-a-custom-intent-filter-to-a-broadcast-receiver
 */
class BotService : Service() {
	private val tag: String = "[${MainActivity.loggerTag}]BotService"
	private var appName: String = ""
	private lateinit var myContext: Context
	private lateinit var overlayView: View
	private lateinit var overlayButton: ImageButton

	private lateinit var playButtonAnimation: Animation
	private lateinit var playButtonAnimationAlt: Animation
	private lateinit var stopButtonAnimation: Animation
	private var currentPlayButtonAnimationType = PlayButtonAnimationType.PULSE_FADE

	// A coroutine scope tied to this service's lifecycle
	private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private var botJob: Job? = null

	/**
	 * Enum to track which play button animation is currently active.
	 */
	private enum class PlayButtonAnimationType {
		PULSE_FADE,
		BOUNCE_FADE
	}

	companion object {
		//		private lateinit var thread: Thread
		private lateinit var windowManager: WindowManager

		// Create the LayoutParams for the floating overlay START/STOP button.
		private val overlayLayoutParams = WindowManager.LayoutParams().apply {
			type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
			} else {
				WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
			}
			flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
			format = PixelFormat.TRANSLUCENT
			width = WindowManager.LayoutParams.WRAP_CONTENT
			height = WindowManager.LayoutParams.WRAP_CONTENT
			windowAnimations = android.R.style.Animation_Toast
		}

		public var isRunning = false
	}

	@SuppressLint("ClickableViewAccessibility", "InflateParams")
	override fun onCreate() {
		super.onCreate()

		myContext = this
		appName = myContext.getString(R.string.app_name)

		// Initialize the animations for the floating overlay button.
		initializeAnimations()

		// Display the overlay view layout on the screen.
		overlayView = LayoutInflater.from(this).inflate(R.layout.bot_actions, null)
		windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
		windowManager.addView(overlayView, overlayLayoutParams)

		// This button is able to be moved around the screen and clicking it will start/stop the game automation.
		overlayButton = overlayView.findViewById(R.id.bot_actions_overlay_button)

		// Start the initial animations for the floating overlay button.
		startAnimations()

		overlayButton.setOnTouchListener(object : View.OnTouchListener {
			private var initialX: Int = 0
			private var initialY: Int = 0
			private var initialTouchX: Float = 0F
			private var initialTouchY: Float = 0F

			override fun onTouch(v: View?, event: MotionEvent?): Boolean {
				val action = event?.action

				if (action == MotionEvent.ACTION_DOWN) {
					// Get the initial position.
					initialX = overlayLayoutParams.x
					initialY = overlayLayoutParams.y

					// Now get the new position.
					initialTouchX = event.rawX
					initialTouchY = event.rawY

					return false
				} else if (action == MotionEvent.ACTION_UP) {
					val elapsedTime: Long = event.eventTime - event.downTime
					if (elapsedTime < 100L) {
						// Update both the Notification and the overlay button to reflect the current bot status.
						if (!isRunning) {
							Log.d(tag, "Bot Service for $appName is now running.")
							Toast.makeText(myContext, "Bot Service for $appName is now running.", Toast.LENGTH_SHORT)
								.show()
							isRunning = true
							NotificationUtils.updateNotification(myContext, isRunning)
							overlayButton.setImageResource(R.drawable.stop_circle_filled)

							// Switch animations from the play to the stop button animations.
							startAnimations()

							var game: Game? = null

//							thread = thread {
							botJob = serviceScope.launch {
								try {
									game = Game(myContext)

									// Clear the Message Log.
									MessageLog.clearLog()
									MessageLog.saveCheck = false

									// Start with the provided settings from SharedPreferences.
									game.start()

									val notificationMessage =
										if (game.notificationMessage != "") game.notificationMessage else "Bot has completed successfully."
									NotificationUtils.updateNotification(myContext, false, notificationMessage)
								} catch (e: CancellationException) {
									// This is thrown when the job is cancelled. It's the expected way to stop.
									NotificationUtils.updateNotification(myContext, false, "Bot was manually stopped.")
								} catch (e: Exception) {
									if (e.toString() == "java.lang.InterruptedException") {
										NotificationUtils.updateNotification(
											myContext,
											false,
											"Bot was manually stopped."
										)
									} else {
										NotificationUtils.updateNotification(
											myContext,
											false,
											"Encountered an Exception: $e.\nTap me to see more details."
										)
										game?.printToLog(
											"$appName encountered an Exception: ${e.stackTraceToString()}",
											tag = tag,
											isError = true
										)
									}
								} finally {
									performCleanUp()
								}
							}
						} else {
//							thread.interrupt()
							botJob?.cancel()
							NotificationUtils.updateNotification(myContext, false, "Bot was manually stopped.")
							performCleanUp()
						}

						// Returning true here freezes the animation of the click on the button.
						return false
					}
				} else if (action == MotionEvent.ACTION_MOVE) {
					val xDiff = (event.rawX - initialTouchX).roundToInt()
					val yDiff = (event.rawY - initialTouchY).roundToInt()

					// Calculate the X and Y coordinates of the view.
					overlayLayoutParams.x = initialX + xDiff
					overlayLayoutParams.y = initialY + yDiff

					// Now update the layout.
					windowManager.updateViewLayout(overlayView, overlayLayoutParams)
					return false
				}

				return false
			}
		})
	}

	/**
	 * Initialize the animations for the floating overlay button.
	 */
	private fun initializeAnimations() {
		playButtonAnimation = AnimationUtils.loadAnimation(this, R.anim.play_button_animation)
		playButtonAnimationAlt = AnimationUtils.loadAnimation(this, R.anim.play_button_animation_alt)
		stopButtonAnimation = AnimationUtils.loadAnimation(this, R.anim.stop_button_animation)

		// Set up animation listeners for continuous cycling.
		setupPlayButtonAnimationListener()
		setupPlayButtonAltAnimationListener()
		setupStopButtonAnimationListener()
	}

	/**
	 * Set up the initial animation listener for the play button animation.
	 */
	private fun setupPlayButtonAnimationListener() {
		playButtonAnimation.setAnimationListener(object : Animation.AnimationListener {
			override fun onAnimationStart(animation: Animation?) {}
			override fun onAnimationEnd(animation: Animation?) {
				if (!isRunning) {
					// Switch animations.
					currentPlayButtonAnimationType = PlayButtonAnimationType.BOUNCE_FADE
					overlayButton.startAnimation(playButtonAnimation)
				}
			}

			override fun onAnimationRepeat(animation: Animation?) {}
		})
	}

	/**
	 * Set up the other animation listener for the play button animation.
	 */
	private fun setupPlayButtonAltAnimationListener() {
		playButtonAnimationAlt.setAnimationListener(object : Animation.AnimationListener {
			override fun onAnimationStart(animation: Animation?) {}
			override fun onAnimationEnd(animation: Animation?) {
				if (!isRunning) {
					// Switch animations.
					currentPlayButtonAnimationType = PlayButtonAnimationType.PULSE_FADE
					overlayButton.startAnimation(playButtonAnimationAlt)
				}
			}

			override fun onAnimationRepeat(animation: Animation?) {}
		})
	}

	/**
	 * Set up the animation listener for the stop button animation.
	 */
	private fun setupStopButtonAnimationListener() {
		stopButtonAnimation.setAnimationListener(object : Animation.AnimationListener {
			override fun onAnimationStart(animation: Animation?) {}
			override fun onAnimationEnd(animation: Animation?) {
				if (isRunning) {
					// Restart the animation.
					overlayButton.startAnimation(stopButtonAnimation)
				}
			}

			override fun onAnimationRepeat(animation: Animation?) {}
		})
	}

	/**
	 * Start the appropriate animations for the floating overlay button based on the bot state.
	 */
	internal fun startAnimations() {
		// Clear any existing animation.
		overlayButton.clearAnimation()

		// Start the appropriate animation based on bot state.
		if (isRunning) {
			overlayButton.startAnimation(stopButtonAnimation)
		} else {
			currentPlayButtonAnimationType = PlayButtonAnimationType.PULSE_FADE
			overlayButton.startAnimation(playButtonAnimationAlt)
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		// Do not attempt to restart the bot service if it crashes.
		return START_NOT_STICKY
	}

	override fun onBind(intent: Intent?): IBinder? {
		return null
	}

	override fun onDestroy() {
		super.onDestroy()

		serviceScope.cancel()

		// Stop animations before removing the view.
		overlayButton.clearAnimation()

		// Remove the overlay View that holds the overlay button.
		windowManager.removeView(overlayView)

		val service = Intent(myContext, MyAccessibilityService::class.java)
		myContext.stopService(service)
	}

	/**
	 * Perform cleanup upon app completion or encountering an Exception.
	 */
	internal fun performCleanUp() {
		// Save the message log.
		MessageLog.saveLogToFile(myContext)

		Log.d(tag, "Bot Service for $appName is now stopped.")
		isRunning = false

		// Reset the overlay button's image and animation on a separate UI thread.
		Handler(Looper.getMainLooper()).post {
			overlayButton.setImageResource(R.drawable.play_circle_filled)
			startAnimations()
		}
	}
}