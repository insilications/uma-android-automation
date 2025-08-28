package com.steve1316.uma_android_automation.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import com.steve1316.uma_android_automation.MainActivity
import com.steve1316.uma_android_automation.bot.Game
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Integer.max
import java.text.DecimalFormat
import androidx.core.graphics.scale
import androidx.core.graphics.get
import androidx.core.graphics.createBitmap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.math.sqrt
import kotlin.text.replace


/**
 * Utility functions for image processing via CV like OpenCV.
 */
class ImageUtils(context: Context, private val game: Game) {
	private val tag: String = "[${MainActivity.loggerTag}]ImageUtils"
	private var myContext = context
	private val matchMethod: Int = Imgproc.TM_CCOEFF_NORMED
	private val decimalFormat = DecimalFormat("#.###")
	private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
	private val tessBaseAPI: TessBaseAPI
	private val tesseractLanguages = arrayListOf("eng")

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// SharedPreferences
	private var sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	private val campaign: String = sharedPreferences.getString("campaign", "")!!
	private var confidence: Double = sharedPreferences.getInt("confidence", 80).toDouble() / 100.0
	private var customScale: Double = sharedPreferences.getInt("customScale", 100).toDouble() / 100.0
	private val debugMode: Boolean = sharedPreferences.getBoolean("debugMode", false)

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////
	// Device configuration
	private val displayWidth: Int = MediaProjectionService.displayWidth
	private val displayHeight: Int = MediaProjectionService.displayHeight
	private val isLowerEnd: Boolean = (displayWidth == 720)
	private val isDefault: Boolean = (displayWidth == 1080)
	val isTablet: Boolean =
		(displayWidth == 1600 && displayHeight == 2560) || (displayWidth == 2560 && displayHeight == 1600) // Galaxy Tab S7 1600x2560 Portrait Mode
	private val isLandscape: Boolean =
		(displayWidth == 2560 && displayHeight == 1600) // Galaxy Tab S7 1600x2560 Landscape Mode
	private val isSplitScreen: Boolean = false // Uma Musume Pretty Derby is only playable in Portrait mode.

	// Scales
	private val lowerEndScales: MutableList<Double> = generateSequence(0.50) { it + 0.01 }
		.takeWhile { it <= 0.70 }
		.toMutableList()

	private val middleEndScales: MutableList<Double> = generateSequence(0.50) { it + 0.01 }
		.takeWhile { it <= 3.00 }
		.toMutableList()

	private val tabletSplitPortraitScales: MutableList<Double> = generateSequence(0.50) { it + 0.01 }
		.takeWhile { it <= 1.00 }
		.toMutableList()

	private val tabletSplitLandscapeScales: MutableList<Double> = generateSequence(0.50) { it + 0.01 }
		.takeWhile { it <= 1.00 }
		.toMutableList()

	private val tabletPortraitScales: MutableList<Double> = generateSequence(1.00) { it + 0.01 }
		.takeWhile { it <= 2.00 }
		.toMutableList()

	// TODO: Separate tablet landscape scale to non-splitscreen and splitscreen scales.

	// Define template matching regions of the screen.
	val regionTopHalf: IntArray = intArrayOf(0, 0, displayWidth, displayHeight / 2)
	val regionBottomHalf: IntArray = intArrayOf(0, displayHeight / 2, displayWidth, displayHeight / 2)
	val regionMiddle: IntArray = intArrayOf(0, displayHeight / 4, displayWidth, displayHeight / 2)

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

	companion object {
		private var matchFilePath: String = ""

		/**
		 * Saves the file path to the saved match image file for debugging purposes.
		 *
		 * @param filePath File path to where to store the image containing the location of where the match was found.
		 */
		internal fun updateMatchFilePath(filePath: String) {
			matchFilePath = filePath
		}
	}

	init {
		// Set the file path to the /files/temp/ folder.
		myContext.getExternalFilesDir(null)?.let { baseDir ->
			val tempDirPath = "${baseDir.absolutePath}/temp"
			updateMatchFilePath(tempDirPath)

			// Also, ensure the directory is created if it doesn't exist.
			File(tempDirPath).mkdirs()
		}

		// Initialize Tesseract with the traineddata model.
		initTesseract()
		tessBaseAPI = TessBaseAPI()

		// Start up Tesseract.
		tessBaseAPI.init(myContext.getExternalFilesDir(null)?.absolutePath + "/tesseract/", "eng")
		game.printToLog("[INFO] Training file loaded.\n", tag = tag)
	}

	data class RaceDetails(
		val fans: Int,
		val hasDoublePredictions: Boolean
	)

	data class ScaleConfidenceResult(
		val scale: Double,
		val confidence: Double
	)

	data class BarFillResult(
		val fillPercent: Double,
		val filledSegments: Int,
		val dominantColor: String
	)

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

	/**
	 * Starts a test to determine what scales are working on this device by looping through some template images.
	 *
	 * @return A mapping of template image names used to test and their lists of working scales.
	 */
	fun startTemplateMatchingTest(): MutableMap<String, MutableList<ScaleConfidenceResult>> {
		val results = mutableMapOf<String, MutableList<ScaleConfidenceResult>>(
			"energy" to mutableListOf(),
			"tazuna" to mutableListOf(),
			"skill_points" to mutableListOf()
		)

		val defaultConfidence = 0.8
		val testScaleDecimalFormat = DecimalFormat("#.##")
		val testConfidenceDecimalFormat = DecimalFormat("#.##")

		for (key in results.keys) {
			val (sourceBitmap, templateBitmap) = getBitmaps(key)

			// First, try the default values of 1.0 for scale and 0.8 for confidence.
			val (success, _) = match(
				sourceBitmap,
				templateBitmap!!,
				key,
				useSingleScale = true,
				customConfidence = defaultConfidence,
				testScale = 1.0
			)
			if (success) {
				game.printToLog("[TEST] Initial test for $key succeeded at the default values.", tag = tag)
				results[key]?.add(ScaleConfidenceResult(1.0, defaultConfidence))
				continue // If it works, skip to the next template.
			}

			// If not, try all scale/confidence combinations.
			val scalesToTest = mutableListOf<Double>()
			var scale = 0.5
			while (scale <= 3.0) {
				scalesToTest.add(testScaleDecimalFormat.format(scale).toDouble())
				scale += 0.1
			}

			for (testScale in scalesToTest) {
				var confidence = 0.6
				while (confidence <= 1.0) {
					val formattedConfidence = testConfidenceDecimalFormat.format(confidence).toDouble()
					val (testSuccess, _) = match(
						sourceBitmap,
						templateBitmap,
						key,
						useSingleScale = true,
						customConfidence = formattedConfidence,
						testScale = testScale
					)
					if (testSuccess) {
						game.printToLog(
							"[TEST] Test for $key succeeded at scale $testScale and confidence $formattedConfidence.",
							tag = tag
						)
						results[key]?.add(ScaleConfidenceResult(testScale, formattedConfidence))
					}
					confidence += 0.1
				}
			}
		}

		return results
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

	/**
	 * Match between the source Bitmap from /files/temp/ and the template Bitmap from the assets folder.
	 *
	 * @param sourceBitmap Bitmap from the /files/temp/ folder.
	 * @param templateBitmap Bitmap from the assets folder.
	 * @param templateName Name of the template image to use in debugging log messages.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param useSingleScale Whether to use only the single custom scale or to use a range based off of it.
	 * @param customConfidence Specify a custom confidence. Defaults to the confidence set in the app's settings.
	 * @param testScale Scale used by testing. Defaults to 0.0 which will fallback to the other scale conditions.
	 * @return Pair of (success: Boolean, location: Point?) where success indicates if a match was found and location contains the match coordinates if found.
	 */
	private fun match(
		sourceBitmap: Bitmap,
		templateBitmap: Bitmap,
		templateName: String,
		region: IntArray = intArrayOf(0, 0, 0, 0),
		useSingleScale: Boolean = false,
		customConfidence: Double = 0.0,
		testScale: Double = 0.0
	): Pair<Boolean, Point?> {
		// If a custom region was specified, crop the source screenshot.
		val srcBitmap = if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
			// Validate region bounds to prevent IllegalArgumentException with creating a crop area that goes beyond the source Bitmap.
			val x = max(0, region[0].coerceAtMost(sourceBitmap.width))
			val y = max(0, region[1].coerceAtMost(sourceBitmap.height))
			val width = region[2].coerceAtMost(sourceBitmap.width - x)
			val height = region[3].coerceAtMost(sourceBitmap.height - y)

			createSafeBitmap(sourceBitmap, x, y, width, height, "match region crop") ?: sourceBitmap
		} else {
			sourceBitmap
		}

		val setConfidence: Double = if (templateName == "training_rainbow") {
			game.printToLog(
				"[INFO] For detection of rainbow training, confidence will be forcibly set to 0.9 to avoid false positives.",
				tag = tag
			)
			0.9
		} else if (customConfidence == 0.0) {
			confidence
		} else {
			customConfidence
		}

		// Scale images if the device is not 1080p which is supported by default.
		val scales: MutableList<Double> = when {
			testScale != 0.0 -> {
				mutableListOf(testScale)
			}

			customScale != 1.0 && !useSingleScale -> {
				mutableListOf(
					customScale - 0.02,
					customScale - 0.01,
					customScale,
					customScale + 0.01,
					customScale + 0.02,
					customScale + 0.03,
					customScale + 0.04
				)
			}

			customScale != 1.0 && useSingleScale -> {
				mutableListOf(customScale)
			}

			isLowerEnd -> {
				lowerEndScales.toMutableList()
			}

			!isLowerEnd && !isDefault && !isTablet -> {
				middleEndScales.toMutableList()
			}

			isTablet && isSplitScreen && isLandscape -> {
				tabletSplitLandscapeScales.toMutableList()
			}

			isTablet && isSplitScreen && !isLandscape -> {
				tabletSplitPortraitScales.toMutableList()
			}

			isTablet && !isSplitScreen && !isLandscape -> {
				tabletPortraitScales.toMutableList()
			}

			else -> {
				mutableListOf(1.0)
			}
		}

		while (scales.isNotEmpty()) {
			val newScale: Double = decimalFormat.format(scales.removeAt(0)).toDouble()

			val tmp: Bitmap = if (newScale != 1.0) {
				templateBitmap.scale(
					(templateBitmap.width * newScale).toInt(),
					(templateBitmap.height * newScale).toInt()
				)
			} else {
				templateBitmap
			}

			// Create the Mats of both source and template images.
			val sourceMat = Mat()
			val templateMat = Mat()
			Utils.bitmapToMat(srcBitmap, sourceMat)
			Utils.bitmapToMat(tmp, templateMat)

			// Clamp template dimensions to source dimensions if template is too large.
			val clampedTemplateMat =
				if (templateMat.cols() > sourceMat.cols() || templateMat.rows() > sourceMat.rows()) {
					Log.d(
						tag,
						"Image sizes for match assertion failed - sourceMat: ${sourceMat.size()}, templateMat: ${templateMat.size()}"
					)
					// Create a new Mat with clamped dimensions.
					val clampedWidth = minOf(templateMat.cols(), sourceMat.cols())
					val clampedHeight = minOf(templateMat.rows(), sourceMat.rows())
					Mat(templateMat, Rect(0, 0, clampedWidth, clampedHeight))
				} else {
					templateMat
				}

			// Make the Mats grayscale for the source and the template.
			Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
			Imgproc.cvtColor(clampedTemplateMat, clampedTemplateMat, Imgproc.COLOR_BGR2GRAY)

			// Create the result matrix.
			val resultColumns: Int = sourceMat.cols() - clampedTemplateMat.cols() + 1
			val resultRows: Int = sourceMat.rows() - clampedTemplateMat.rows() + 1
			val resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)

			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, clampedTemplateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

			var matchLocation = Point()
			var matchCheck = false

			// Format minVal or maxVal.
			val minVal: Double = decimalFormat.format(mmr.minVal).toDouble()
			val maxVal: Double = decimalFormat.format(mmr.maxVal).toDouble()

			// Depending on which matching method was used, the algorithms determine which location was the best.
			if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				matchLocation = mmr.minLoc
				matchCheck = true
				if (debugMode) {
					game.printToLog(
						"[DEBUG] Match found for \"$templateName\" with $minVal <= ${1.0 - setConfidence} at Point $matchLocation using scale: $newScale.",
						tag = tag
					)
				}
			} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				matchLocation = mmr.maxLoc
				matchCheck = true
				if (debugMode) {
					game.printToLog(
						"[DEBUG] Match found for \"$templateName\" with $maxVal >= $setConfidence at Point $matchLocation using scale: $newScale.",
						tag = tag
					)
				}
			} else {
				if (debugMode) {
					if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED)) {
						game.printToLog(
							"[DEBUG] Match not found for \"$templateName\" with $maxVal not >= $setConfidence at Point ${mmr.maxLoc} using scale $newScale.",
							tag = tag
						)
					} else {
						game.printToLog(
							"[DEBUG] Match not found for \"$templateName\" with $minVal not <= ${1.0 - setConfidence} at Point ${mmr.minLoc} using scale $newScale.",
							tag = tag
						)
					}
				}
			}

			if (matchCheck) {
				if (debugMode && matchFilePath != "") {
					// Draw a rectangle around the supposed best matching location and then save the match into a file in /files/temp/ directory. This is for debugging purposes to see if this
					// algorithm found the match accurately or not.
					Imgproc.rectangle(
						sourceMat,
						matchLocation,
						Point(matchLocation.x + templateMat.cols(), matchLocation.y + templateMat.rows()),
						Scalar(0.0, 0.0, 0.0),
						10
					)
					Imgcodecs.imwrite("$matchFilePath/match.png", sourceMat)
				}

				// Center the coordinates so that any tap gesture would be directed at the center of that match location instead of the default
				// position of the top left corner of the match location.
				matchLocation.x += (templateMat.cols() / 2)
				matchLocation.y += (templateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
					matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
				}

				return Pair(true, matchLocation)
			}

			if (!BotService.isRunning) {
				throw InterruptedException()
			}

			sourceMat.release()
			templateMat.release()
			clampedTemplateMat.release()
			resultMat.release()
		}

		return Pair(false, null)
	}

	/**
	 * Search through the whole source screenshot for all matches to the template image.
	 *
	 * @param sourceBitmap Bitmap from the /files/temp/ folder.
	 * @param templateBitmap Bitmap from the assets folder.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param customConfidence Specify a custom confidence. Defaults to the confidence set in the app's settings.
	 * @return ArrayList of Point objects that represents the matches found on the source screenshot.
	 */
	private fun matchAll(
		sourceBitmap: Bitmap,
		templateBitmap: Bitmap,
		region: IntArray = intArrayOf(0, 0, 0, 0),
		customConfidence: Double = 0.0
	): java.util.ArrayList<Point> {
		// Create a local matchLocations list for this method
		var matchLocation = Point()
		val matchLocations = arrayListOf<Point>()

		// If a custom region was specified, crop the source screenshot.
		val srcBitmap = if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
			// Validate region bounds to prevent IllegalArgumentException with creating a crop area that goes beyond the source Bitmap.
			val x = max(0, region[0].coerceAtMost(sourceBitmap.width))
			val y = max(0, region[1].coerceAtMost(sourceBitmap.height))
			val width = region[2].coerceAtMost(sourceBitmap.width - x)
			val height = region[3].coerceAtMost(sourceBitmap.height - y)

			Log.d(
				tag,
				"[DEBUG] matchAll - sourceBitmap.width: ${sourceBitmap.width}, sourceBitmap.height: ${sourceBitmap.height}"
			)
			Log.d(
				tag,
				"[DEBUG] matchAll - sourceBitmap.width - x: ${sourceBitmap.width - x}, sourceBitmap.height - y: ${sourceBitmap.height - y}"
			)
			Log.d(tag, "[DEBUG] matchAll - x: $x, y: $y, width: $width, height: $height")

			createSafeBitmap(sourceBitmap, x, y, width, height, "matchAll region crop") ?: sourceBitmap
		} else {
			sourceBitmap
		}

		// Scale images if the device is not 1080p which is supported by default.
		val scales: MutableList<Double> = when {
			customScale != 1.0 -> {
				mutableListOf(
					customScale - 0.02,
					customScale - 0.01,
					customScale,
					customScale + 0.01,
					customScale + 0.02,
					customScale + 0.03,
					customScale + 0.04
				)
			}

			isLowerEnd -> {
				lowerEndScales.toMutableList()
			}

			!isLowerEnd && !isDefault && !isTablet -> {
				middleEndScales.toMutableList()
			}

			isTablet && isSplitScreen && isLandscape -> {
				tabletSplitLandscapeScales.toMutableList()
			}

			isTablet && isSplitScreen && !isLandscape -> {
				tabletSplitPortraitScales.toMutableList()
			}

			isTablet && !isSplitScreen && !isLandscape -> {
				tabletPortraitScales.toMutableList()
			}

			else -> {
				mutableListOf(1.0)
			}
		}

		val setConfidence: Double = if (customConfidence == 0.0) {
			confidence
		} else {
			customConfidence
		}

		var matchCheck = false
		var newScale = 0.0
		val sourceMat = Mat()
		val templateMat = Mat()
		var resultMat = Mat()
		var clampedTemplateMat: Mat? = null

		val debugMat = Mat()
		Utils.bitmapToMat(srcBitmap, debugMat)
		saveDebugImage(matchFilePath, "matchAll_debugMat.png", debugMat)
		debugMat.release()

		// Set templateMat at whatever scale it found the very first match for the next while loop.
		while (!matchCheck && scales.isNotEmpty()) {
			newScale = decimalFormat.format(scales.removeAt(0)).toDouble()

			val tmp: Bitmap = if (newScale != 1.0) {
				templateBitmap.scale(
					(templateBitmap.width * newScale).toInt(),
					(templateBitmap.height * newScale).toInt()
				)
			} else {
				templateBitmap
			}

			// Create the Mats of both source and template images.
			Utils.bitmapToMat(srcBitmap, sourceMat)
			Utils.bitmapToMat(tmp, templateMat)

			// Clamp template dimensions to source dimensions if template is too large.
			clampedTemplateMat = if (templateMat.cols() > sourceMat.cols() || templateMat.rows() > sourceMat.rows()) {
				Log.d(
					tag,
					"Image sizes for matchAll assertion failed - sourceMat: ${sourceMat.size()}, templateMat: ${templateMat.size()}"
				)
				// Create a new Mat with clamped dimensions.
				val clampedWidth = minOf(templateMat.cols(), sourceMat.cols())
				val clampedHeight = minOf(templateMat.rows(), sourceMat.rows())
				Mat(templateMat, Rect(0, 0, clampedWidth, clampedHeight))
			} else {
				templateMat
			}

			// Make the Mats grayscale for the source and the template.
			Imgproc.cvtColor(sourceMat, sourceMat, Imgproc.COLOR_BGR2GRAY)
			Imgproc.cvtColor(clampedTemplateMat, clampedTemplateMat, Imgproc.COLOR_BGR2GRAY)

			// Create the result matrix.
			val resultColumns: Int = sourceMat.cols() - clampedTemplateMat.cols() + 1
			val resultRows: Int = sourceMat.rows() - clampedTemplateMat.rows() + 1
			if (resultColumns < 0 || resultRows < 0) {
				break
			}

			resultMat = Mat(resultRows, resultColumns, CvType.CV_32FC1)

			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, clampedTemplateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

			// Depending on which matching method was used, the algorithms determine which location was the best.
			if ((matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				matchLocation = mmr.minLoc
				matchCheck = true

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(
					sourceMat,
					matchLocation,
					Point(matchLocation.x + clampedTemplateMat.cols(), matchLocation.y + clampedTemplateMat.rows()),
					Scalar(0.0, 0.0, 0.0),
					20
				)

				// Center the location coordinates and then save it.
				matchLocation.x += (clampedTemplateMat.cols() / 2)
				matchLocation.y += (clampedTemplateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
					matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
				}

				matchLocations.add(matchLocation)
			} else if ((matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				matchLocation = mmr.maxLoc
				matchCheck = true

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(
					sourceMat,
					matchLocation,
					Point(matchLocation.x + clampedTemplateMat.cols(), matchLocation.y + clampedTemplateMat.rows()),
					Scalar(0.0, 0.0, 0.0),
					20
				)

				// Center the location coordinates and then save it.
				matchLocation.x += (clampedTemplateMat.cols() / 2)
				matchLocation.y += (clampedTemplateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					matchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + matchLocation.x))
					matchLocation.y = sourceBitmap.height - (sourceBitmap.height - (region[1] + matchLocation.y))
				}

				matchLocations.add(matchLocation)
			}

			if (!BotService.isRunning) {
				throw InterruptedException()
			}
		}

		// Loop until all other matches are found and break out when there are no more to be found.
		while (matchCheck) {
			// Now perform the matching and localize the result.
			Imgproc.matchTemplate(sourceMat, clampedTemplateMat, resultMat, matchMethod)
			val mmr: Core.MinMaxLocResult = Core.minMaxLoc(resultMat)

			// Format minVal or maxVal.
			val minVal: Double = decimalFormat.format(mmr.minVal).toDouble()
			val maxVal: Double = decimalFormat.format(mmr.maxVal).toDouble()

			if (clampedTemplateMat != null && (matchMethod == Imgproc.TM_SQDIFF || matchMethod == Imgproc.TM_SQDIFF_NORMED) && mmr.minVal <= (1.0 - setConfidence)) {
				val tempMatchLocation: Point = mmr.minLoc

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(
					sourceMat,
					tempMatchLocation,
					Point(
						tempMatchLocation.x + clampedTemplateMat.cols(),
						tempMatchLocation.y + clampedTemplateMat.rows()
					),
					Scalar(0.0, 0.0, 0.0),
					20
				)

//				if (debugMode) {
				game.printToLog(
					"[DEBUG] Match All found with $minVal <= ${1.0 - setConfidence} at Point $matchLocation with scale: $newScale.",
					tag = tag
				)
				Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
//				}

				// Center the location coordinates and then save it.
				tempMatchLocation.x += (clampedTemplateMat.cols() / 2)
				tempMatchLocation.y += (clampedTemplateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					tempMatchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + tempMatchLocation.x))
					tempMatchLocation.y =
						sourceBitmap.height - (sourceBitmap.height - (region[1] + tempMatchLocation.y))
				}

				if (!matchLocations.contains(tempMatchLocation) && !matchLocations.contains(
						Point(
							tempMatchLocation.x + 1.0,
							tempMatchLocation.y
						)
					) &&
					!matchLocations.contains(
						Point(
							tempMatchLocation.x,
							tempMatchLocation.y + 1.0
						)
					) && !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y + 1.0))
				) {
					matchLocations.add(tempMatchLocation)
				}
			} else if (clampedTemplateMat != null && (matchMethod != Imgproc.TM_SQDIFF && matchMethod != Imgproc.TM_SQDIFF_NORMED) && mmr.maxVal >= setConfidence) {
				val tempMatchLocation: Point = mmr.maxLoc

				// Draw a rectangle around the match on the source Mat. This will prevent false positives and infinite looping on subsequent matches.
				Imgproc.rectangle(
					sourceMat,
					tempMatchLocation,
					Point(
						tempMatchLocation.x + clampedTemplateMat.cols(),
						tempMatchLocation.y + clampedTemplateMat.rows()
					),
					Scalar(0.0, 0.0, 0.0),
					20
				)

//				if (debugMode) {
				game.printToLog(
					"[DEBUG] Match All found with $maxVal >= $setConfidence at Point $matchLocation with scale: $newScale.",
					tag = tag
				)
				Imgcodecs.imwrite("$matchFilePath/matchAll.png", sourceMat)
//				}

				// Center the location coordinates and then save it.
				tempMatchLocation.x += (clampedTemplateMat.cols() / 2)
				tempMatchLocation.y += (clampedTemplateMat.rows() / 2)

				// If a custom region was specified, readjust the coordinates to reflect the fullscreen source screenshot.
				if (!region.contentEquals(intArrayOf(0, 0, 0, 0))) {
					tempMatchLocation.x = sourceBitmap.width - (sourceBitmap.width - (region[0] + tempMatchLocation.x))
					tempMatchLocation.y =
						sourceBitmap.height - (sourceBitmap.height - (region[1] + tempMatchLocation.y))
				}

				if (!matchLocations.contains(tempMatchLocation) && !matchLocations.contains(
						Point(
							tempMatchLocation.x + 1.0,
							tempMatchLocation.y
						)
					) &&
					!matchLocations.contains(
						Point(
							tempMatchLocation.x,
							tempMatchLocation.y + 1.0
						)
					) && !matchLocations.contains(Point(tempMatchLocation.x + 1.0, tempMatchLocation.y + 1.0))
				) {
					matchLocations.add(tempMatchLocation)
				}
			} else {
				break
			}

			if (!BotService.isRunning) {
				throw InterruptedException()
			}
		}

		sourceMat.release()
		templateMat.release()
		clampedTemplateMat?.release()
		resultMat.release()

		return matchLocations
	}

	/**
	 * Convert absolute x-coordinate on 1080p to relative coordinate on different resolutions for the width.
	 *
	 * @param oldX The old absolute x-coordinate based off of the 1080p resolution.
	 * @return The new relative x-coordinate based off of the current resolution.
	 */
	fun relWidth(oldX: Int): Int {
		return if (isDefault) {
			oldX
		} else {
			(oldX.toDouble() * (displayWidth.toDouble() / 1080.0)).toInt()
		}
	}

	/**
	 * Convert absolute y-coordinate on 1080p to relative coordinate on different resolutions for the height.
	 *
	 * @param oldY The old absolute y-coordinate based off of the 1080p resolution.
	 * @return The new relative y-coordinate based off of the current resolution.
	 */
	fun relHeight(oldY: Int): Int {
		return if (isDefault) {
			oldY
		} else {
			(oldY.toDouble() * (displayHeight.toDouble() / 2340.0)).toInt()
		}
	}

	/**
	 * Helper function to calculate the x-coordinate with relative offset.
	 *
	 * @param baseX The base x-coordinate.
	 * @param offset The offset to add/subtract from the base coordinate and to make relative to.
	 * @return The calculated relative x-coordinate.
	 */
	fun relX(baseX: Double, offset: Int): Int {
		return baseX.toInt() + relWidth(offset)
	}

	/**
	 * Helper function to calculate relative y-coordinate with relative offset.
	 *
	 * @param baseY The base y-coordinate.
	 * @param offset The offset to add/subtract from the base coordinate and to make relative to.
	 * @return The calculated relative y-coordinate.
	 */
	fun relY(baseY: Double, offset: Int): Int {
		return baseY.toInt() + relHeight(offset)
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

	/**
	 * Open the source and template image files and return Bitmaps for them.
	 *
	 * @param templateName File name of the template image.
	 * @return A Pair of source and template Bitmaps.
	 */
	fun getBitmaps(templateName: String): Pair<Bitmap, Bitmap?> {
		var sourceBitmap: Bitmap? = null

		while (sourceBitmap == null) {
			sourceBitmap = MediaProjectionService.takeScreenshotNow(saveImage = debugMode)
		}

		var templateBitmap: Bitmap?

		// Get the Bitmap from the template image file inside the specified folder.
		myContext.assets?.open("images/$templateName.png").use { inputStream ->
			// Get the Bitmap from the template image file and then start matching.
			templateBitmap = BitmapFactory.decodeStream(inputStream)
		}

		return if (templateBitmap != null) {
			Pair(sourceBitmap, templateBitmap)
		} else {
			if (debugMode) {
				game.printToLog("[ERROR] The template Bitmap is null.", tag = tag, isError = true)
			}

			Pair(sourceBitmap, templateBitmap)
		}
	}

	/**
	 * Acquire the Bitmap for only the source screenshot.
	 *
	 * @return Bitmap of the source screenshot.
	 */
	private fun getSourceBitmap(): Bitmap {
		var sourceBitmap: Bitmap? = null
		while (sourceBitmap == null) {
			sourceBitmap = MediaProjectionService.takeScreenshotNow(saveImage = debugMode)
		}

		return sourceBitmap
	}

	/**
	 * Safely creates a bitmap with bounds checking to prevent IllegalArgumentException.
	 * Clamps individual dimensions to source bitmap bounds if they exceed limits.
	 *
	 * @param sourceBitmap The source bitmap to crop from.
	 * @param x The x coordinate for the crop.
	 * @param y The y coordinate for the crop.
	 * @param width The width of the crop.
	 * @param height The height of the crop.
	 * @param context String describing the context for error logging.
	 * @return The cropped bitmap or null if bounds are still invalid after clamping.
	 */
	private fun createSafeBitmap(
		sourceBitmap: Bitmap,
		x: Int,
		y: Int,
		width: Int,
		height: Int,
		context: String
	): Bitmap? {
		// Clamp individual dimensions to source bitmap bounds.
		val clampedX = x.coerceIn(0, sourceBitmap.width)
		val clampedY = y.coerceIn(0, sourceBitmap.height)
		val clampedWidth = width.coerceIn(1, sourceBitmap.width - clampedX)
		val clampedHeight = height.coerceIn(1, sourceBitmap.height - clampedY)

		// Check if any dimensions were clamped and log a warning.
		if (x != clampedX || y != clampedY || width != clampedWidth || height != clampedHeight) {
			game.printToLog(
				"[WARNING] Clamped bounds for $context: original(x=$x, y=$y, width=$width, height=$height) -> clamped(x=$clampedX, y=$clampedY, width=$clampedWidth, height=$clampedHeight), sourceBitmap=${sourceBitmap.width}x${sourceBitmap.height}",
				tag = tag
			)
		}

		// Final validation to ensure the clamped dimensions are still valid.
		if (clampedX < 0 || clampedY < 0 || clampedWidth <= 0 || clampedHeight <= 0 ||
			clampedX + clampedWidth > sourceBitmap.width || clampedY + clampedHeight > sourceBitmap.height
		) {
			game.printToLog(
				"[ERROR] Invalid bounds for $context after clamping: x=$clampedX, y=$clampedY, width=$clampedWidth, height=$clampedHeight, sourceBitmap=${sourceBitmap.width}x${sourceBitmap.height}",
				tag = tag,
				isError = true
			)
			return null
		}

		return Bitmap.createBitmap(sourceBitmap, clampedX, clampedY, clampedWidth, clampedHeight)
	}

	/**
	 * Finds the location of the specified image from the /images/ folder inside assets.
	 *
	 * @param templateName File name of the template image.
	 * @param tries Number of tries before failing. Defaults to 5.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param suppressError Whether or not to suppress saving error messages to the log. Defaults to false.
	 * @return Pair object consisting of the Point object containing the location of the match and the source screenshot. Can be null.
	 */
	fun findImage(
		templateName: String,
		tries: Int = 5,
		region: IntArray = intArrayOf(0, 0, 0, 0),
		suppressError: Boolean = false
	): Pair<Point?, Bitmap> {
		var numberOfTries = tries

		if (debugMode) {
			game.printToLog(
				"\n[DEBUG] Starting process to find the ${templateName.uppercase()} button image...",
				tag = tag
			)
		}

		var (sourceBitmap, templateBitmap) = getBitmaps(templateName)

		while (numberOfTries > 0) {
			if (templateBitmap != null) {
				val (resultFlag, location) = match(sourceBitmap, templateBitmap, templateName, region)
				if (!resultFlag) {
					numberOfTries -= 1
					if (numberOfTries <= 0) {
						if (debugMode && !suppressError) {
							game.printToLog(
								"[WARNING] Failed to find the ${templateName.uppercase()} button.",
								tag = tag
							)
						}

						break
					}

					Log.d(tag, "Failed to find the ${templateName.uppercase()} button. Trying again...")
					game.wait(0.1)
					sourceBitmap = getSourceBitmap()
				} else {
					game.printToLog("[SUCCESS] Found the ${templateName.uppercase()} at $location.", tag = tag)
					return Pair(location, sourceBitmap)
				}
			}
		}

		return Pair(null, sourceBitmap)
	}

	/**
	 * Confirms whether or not the bot is at the specified location from the /headers/ folder inside assets.
	 *
	 * @param templateName File name of the template image.
	 * @param tries Number of tries before failing. Defaults to 5.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @param suppressError Whether or not to suppress saving error messages to the log.
	 * @return True if the current location is at the specified location. False otherwise.
	 */
	fun confirmLocation(
		templateName: String,
		tries: Int = 5,
		region: IntArray = intArrayOf(0, 0, 0, 0),
		suppressError: Boolean = false
	): Boolean {
		var numberOfTries = tries

		if (debugMode) {
			game.printToLog(
				"\n[DEBUG] Starting process to find the ${templateName.uppercase()} header image...",
				tag = tag
			)
		}

		var (sourceBitmap, templateBitmap) = getBitmaps(templateName + "_header")

		while (numberOfTries > 0) {
			if (templateBitmap != null) {
				val (resultFlag, _) = match(sourceBitmap, templateBitmap, templateName, region)
				if (!resultFlag) {
					numberOfTries -= 1
					if (numberOfTries <= 0) {
						break
					}

					game.wait(0.1)
					sourceBitmap = getSourceBitmap()
				} else {
					game.printToLog(
						"[SUCCESS] Current location confirmed to be at ${templateName.uppercase()}.",
						tag = tag
					)
					return true
				}
			} else {
				break
			}
		}

		if (debugMode && !suppressError) {
			game.printToLog("[WARNING] Failed to confirm the bot location at ${templateName.uppercase()}.", tag = tag)
		}

		return false
	}

	/**
	 * Finds all occurrences of the specified image in the images folder.
	 *
	 * @param templateName File name of the template image.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @return An ArrayList of Point objects containing all the occurrences of the specified image or null if not found.
	 */
	fun findAll(templateName: String, region: IntArray = intArrayOf(0, 0, 0, 0)): ArrayList<Point> {
		val (sourceBitmap, templateBitmap) = getBitmaps(templateName)

		if (templateBitmap != null) {
			val matchLocations = matchAll(sourceBitmap, templateBitmap, region = region)

			// Sort the match locations by ascending x and y coordinates.
			matchLocations.sortBy { it.x }
			matchLocations.sortBy { it.y }

			if (debugMode) {
				game.printToLog("[DEBUG] Found match locations for $templateName: $matchLocations.", tag = tag)
			} else {
				Log.d(tag, "[DEBUG] Found match locations for $templateName: $matchLocations.")
			}

			return matchLocations
		}

		return arrayListOf()
	}

	/**
	 * Find all occurrences of the specified image in the images folder using a provided source bitmap. Useful for parallel processing to avoid exceeding the maxImages buffer.
	 *
	 * @param templateName File name of the template image.
	 * @param sourceBitmap The source bitmap to search in.
	 * @param region Specify the region consisting of (x, y, width, height) of the source screenshot to template match. Defaults to (0, 0, 0, 0) which is equivalent to searching the full image.
	 * @return An ArrayList of Point objects containing all the occurrences of the specified image or null if not found.
	 */
	private fun findAllWithBitmap(
		templateName: String,
		sourceBitmap: Bitmap,
		region: IntArray = intArrayOf(0, 0, 0, 0)
	): ArrayList<Point> {
		var templateBitmap: Bitmap?
		myContext.assets?.open("images/$templateName.png").use { inputStream ->
			templateBitmap = BitmapFactory.decodeStream(inputStream)
		}

		if (templateBitmap != null) {
			val matchLocations = matchAll(sourceBitmap, templateBitmap, region = region)

			// Sort the match locations by ascending x and y coordinates.
			matchLocations.sortBy { it.x }
			matchLocations.sortBy { it.y }

			if (debugMode) {
				game.printToLog("[DEBUG] Found match locations for $templateName: $matchLocations.", tag = tag)
			} else {
				Log.d(tag, "[DEBUG] Found match locations for $templateName: $matchLocations.")
			}

			return matchLocations
		}

		return arrayListOf()
	}

	/**
	 * Check if the color at the specified coordinates matches the given RGB value.
	 *
	 * @param x X coordinate to check.
	 * @param y Y coordinate to check.
	 * @param rgb Expected RGB values as red, blue and green (0-255).
	 * @param tolerance Tolerance for color matching (0-255). Defaults to 0 for exact match.
	 * @return True if the color at the coordinates matches the expected RGB values within tolerance, false otherwise.
	 */
	fun checkColorAtCoordinates(x: Int, y: Int, rgb: IntArray, tolerance: Int = 0): Boolean {
		val sourceBitmap = getSourceBitmap()

		// Check if coordinates are within bounds.
		if (x < 0 || y < 0 || x >= sourceBitmap.width || y >= sourceBitmap.height) {
			if (debugMode) game.printToLog(
				"[WARNING] Coordinates ($x, $y) are out of bounds for bitmap size ${sourceBitmap.width}x${sourceBitmap.height}",
				tag = tag
			)
			return false
		}

		// Get the pixel color at the specified coordinates.
		val pixel = sourceBitmap[x, y]

		// Extract RGB values from the pixel.
		val actualRed = android.graphics.Color.red(pixel)
		val actualGreen = android.graphics.Color.green(pixel)
		val actualBlue = android.graphics.Color.blue(pixel)

		// Check if the colors match within the specified tolerance.
		val redMatch = kotlin.math.abs(actualRed - rgb[0]) <= tolerance
		val greenMatch = kotlin.math.abs(actualGreen - rgb[1]) <= tolerance
		val blueMatch = kotlin.math.abs(actualBlue - rgb[2]) <= tolerance

		if (debugMode) {
			game.printToLog(
				"[DEBUG] Color check at ($x, $y): Expected RGB(${rgb[0]}, ${rgb[1]}, ${rgb[2]}), Actual RGB($actualRed, $actualGreen, $actualBlue), Match: ${redMatch && greenMatch && blueMatch}",
				tag = tag
			)
		}

		return redMatch && greenMatch && blueMatch
	}

	////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////

	/**
	 * Perform OCR text detection using Tesseract along with some image manipulation via thresholding to make the cropped screenshot black and white using OpenCV.
	 *
	 * @param increment Increments the threshold by this value. Defaults to 0.0.
	 * @return The detected String in the cropped region.
	 */
	fun findText(increment: Double = 0.0): String {
		val (sourceBitmap, templateBitmap) = getBitmaps("shift")

		// Acquire the location of the energy text image.
		val (_, energyTemplateBitmap) = getBitmaps("energy")
		val (_, matchLocation) = match(sourceBitmap, energyTemplateBitmap!!, "energy")
		if (matchLocation == null) {
			game.printToLog("[WARNING] Could not proceed with OCR text detection due to not being able to find the energy template on the source image.")
			return "empty!"
		}

		// Use the match location acquired from finding the energy text image and acquire the (x, y) coordinates of the event title container right below the location of the energy text image.
		val newX: Int
		val newY: Int
		var croppedBitmap: Bitmap? = if (isTablet) {
			newX = max(0, matchLocation.x.toInt() - relWidth(250))
			newY = max(0, matchLocation.y.toInt() + relHeight(154))
			createSafeBitmap(sourceBitmap, newX, newY, relWidth(746), relHeight(85), "findText tablet crop")
		} else {
			newX = max(0, matchLocation.x.toInt() - relWidth(125))
			newY = max(0, matchLocation.y.toInt() + relHeight(116))
			createSafeBitmap(sourceBitmap, newX, newY, relWidth(645), relHeight(65), "findText phone crop")
		}
		if (croppedBitmap == null) {
			game.printToLog("[ERROR] Failed to create cropped bitmap for text detection", tag = tag, isError = true)
			return "empty!"
		}

		val tempImage = Mat()
		Utils.bitmapToMat(croppedBitmap, tempImage)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText.png", tempImage)

		// Now see if it is necessary to shift the cropped region over by 70 pixels or not to account for certain events.
		val (shiftMatch, _) = match(croppedBitmap, templateBitmap!!, "shift")
		croppedBitmap = if (shiftMatch) {
			Log.d(tag, "Shifting the region over by 70 pixels!")
			createSafeBitmap(sourceBitmap, relX(newX.toDouble(), 70), newY, 645 - 70, 65, "findText shifted crop")
				?: croppedBitmap
		} else {
			Log.d(tag, "Do not need to shift.")
			croppedBitmap
		}

		// Make the cropped screenshot grayscale.
		val cvImage = Mat()
		Utils.bitmapToMat(croppedBitmap, cvImage)
		Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)

		// Save the cropped image before converting it to black and white in order to troubleshoot issues related to differing device sizes and cropping.
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText_afterCrop.png", cvImage)

		// Thresh the grayscale cropped image to make it black and white.
		val bwImage = Mat()
		val threshold = sharedPreferences.getInt("threshold", 230)
		Imgproc.threshold(cvImage, bwImage, threshold.toDouble() + increment, 255.0, Imgproc.THRESH_BINARY)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugEventTitleText_afterThreshold.png", bwImage)

		// Convert the Mat directly to Bitmap and then pass it to the text reader.
		val resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
		Utils.matToBitmap(bwImage, resultBitmap)
		tessBaseAPI.setImage(resultBitmap)

		// Set the Page Segmentation Mode to '--psm 7' or "Treat the image as a single text line" according to https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method
		tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE

		var result = "empty!"
		try {
			// Finally, detect text on the cropped region.
			result = tessBaseAPI.utF8Text
			game.printToLog("[INFO] Detected text with Tesseract: $result", tag = tag)
		} catch (e: Exception) {
			game.printToLog("[ERROR] Cannot perform OCR: ${e.stackTraceToString()}", tag = tag, isError = true)
		}

		tessBaseAPI.clear()
		tempImage.release()
		cvImage.release()
		bwImage.release()

		return result
	}

	/**
	 * Find the success percentage chance on the currently selected stat. Parameters are optional to allow for thread-safe operations.
	 *
	 * @param sourceBitmap Bitmap of the source image separately taken. Defaults to null.
	 * @param trainingSelectionLocation Point location of the template image separately taken. Defaults to null.
	 *
	 * @return Integer representing the percentage.
	 */
	fun findTrainingFailureChance(
		training: String,
		sourceBitmap: Bitmap? = null,
		trainingSelectionLocation: Point? = null
	): Int {
		// Crop the source screenshot to hold the success percentage only.
		val (trainingSelectionLocation, sourceBitmap) = if (sourceBitmap == null && trainingSelectionLocation == null) {
			findImage("training_failure_chance")
		} else {
			Pair(trainingSelectionLocation, sourceBitmap)
		}

		if (trainingSelectionLocation == null) {
			return -1
		}

		val croppedBitmap: Bitmap? = if (isTablet) {
			createSafeBitmap(
				sourceBitmap!!,
				relX(trainingSelectionLocation.x, -65),
				relY(trainingSelectionLocation.y, 23),
				relWidth(130),
				relHeight(50),
				"findTrainingFailureChance tablet"
			)
		} else {
			createSafeBitmap(
				sourceBitmap!!,
				relX(trainingSelectionLocation.x, -45),
				relY(trainingSelectionLocation.y, 15),
				relWidth(100),
				relHeight(37),
				"findTrainingFailureChance phone"
			)
		}
		if (croppedBitmap == null) {
			game.printToLog(
				"[ERROR] Failed to create cropped bitmap for training failure chance detection.",
				tag = tag,
				isError = true
			)
			return -1
		}

		val resizedBitmap = croppedBitmap.scale(croppedBitmap.width * 2, croppedBitmap.height * 2)

		// Save the cropped image for debugging purposes.
		val tempMat = Mat()
		Utils.bitmapToMat(resizedBitmap, tempMat)
		Imgproc.cvtColor(tempMat, tempMat, Imgproc.COLOR_BGR2GRAY)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugTrainingFailureChance_afterCrop_$training.png", tempMat)

		// Create a InputImage object for Google's ML OCR.
		val resultBitmap = createBitmap(tempMat.cols(), tempMat.rows())
		Utils.matToBitmap(tempMat, resultBitmap)
		val inputImage: InputImage = InputImage.fromBitmap(resultBitmap, 0)

		// Use CountDownLatch to make the async operation synchronous.
		val latch = CountDownLatch(1)
		var result = -1
		var mlkitFailed = false

		textRecognizer.process(inputImage)
			.addOnSuccessListener { text ->
				if (text.textBlocks.isNotEmpty()) {
					for (block in text.textBlocks) {
						try {
							game.printToLog(
								"[INFO] Detected Training failure chance with Google ML Kit: ${block.text}",
								tag = tag
							)
							result = block.text.replace("%", "").trim().toInt()
						} catch (_: NumberFormatException) {
						}
					}
				}
				latch.countDown()
			}
			.addOnFailureListener {
				game.printToLog(
					"[ERROR] Failed to do text detection via Google's ML Kit. Falling back to Tesseract.",
					tag = tag,
					isError = true
				)
				mlkitFailed = true
				latch.countDown()
			}

		// Wait for the async operation to complete.
		try {
			latch.await(5, TimeUnit.SECONDS)
		} catch (_: InterruptedException) {
			game.printToLog("[ERROR] Google ML Kit operation timed out", tag = tag, isError = true)
		}

		// Fallback to Tesseract if ML Kit failed or didn't find result.
		if (mlkitFailed || result == -1) {
			tessBaseAPI.setImage(resultBitmap)
			tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE

			try {
				val detectedText = tessBaseAPI.utF8Text.replace("%", "")
				game.printToLog("[INFO] Detected training failure chance with Tesseract: $detectedText", tag = tag)
				val cleanedResult = detectedText.replace(Regex("[^0-9]"), "")
				result = cleanedResult.toInt()
			} catch (_: NumberFormatException) {
				game.printToLog(
					"[ERROR] Could not convert \"${tessBaseAPI.utF8Text.replace("%", "")}\" to integer.",
					tag = tag,
					isError = true
				)
				result = -1
			} catch (e: Exception) {
				game.printToLog(
					"[ERROR] Cannot perform OCR using Tesseract: ${e.stackTraceToString()}",
					tag = tag,
					isError = true
				)
				result = -1
			}

			tessBaseAPI.clear()
		}

		if (debugMode) {
			game.printToLog("[DEBUG] Failure chance detected to be at $result%.")
		} else {
			Log.d(tag, "Failure chance detected to be at $result%.")
		}

		tempMat.release()

		return result
	}

	/**
	 * Determines the day number to see if today is eligible for doing an extra race.
	 *
	 * @return Number of the day.
	 */
	fun determineDayForExtraRace(): Int {
		var result = -1
		val (energyTextLocation, sourceBitmap) = findImage("energy", tries = 1, region = regionTopHalf)

		if (energyTextLocation != null) {
			// Crop the source screenshot to only contain the day number.
			val croppedBitmap: Bitmap? = if (campaign == "Ao Haru") {
				if (isTablet) {
					createSafeBitmap(
						sourceBitmap,
						relX(energyTextLocation.x, -(260 * 1.32).toInt()),
						relY(energyTextLocation.y, -(140 * 1.32).toInt()),
						relWidth(135),
						relHeight(100),
						"determineDayForExtraRace Ao Haru tablet"
					)
				} else {
					createSafeBitmap(
						sourceBitmap,
						relX(energyTextLocation.x, -260),
						relY(energyTextLocation.y, -140),
						relWidth(105),
						relHeight(75),
						"determineDayForExtraRace Ao Haru phone"
					)
				}
			} else {
				if (isTablet) {
					createSafeBitmap(
						sourceBitmap,
						relX(energyTextLocation.x, -(246 * 1.32).toInt()),
						relY(energyTextLocation.y, -(96 * 1.32).toInt()),
						relWidth(175),
						relHeight(116),
						"determineDayForExtraRace default tablet"
					)
				} else {
					createSafeBitmap(
						sourceBitmap,
						relX(energyTextLocation.x, -246),
						relY(energyTextLocation.y, -100),
						relWidth(140),
						relHeight(95),
						"determineDayForExtraRace default phone"
					)
				}
			}
			if (croppedBitmap == null) {
				game.printToLog("[ERROR] Failed to create cropped bitmap for day detection.", tag = tag, isError = true)
				return -1
			}

			val resizedBitmap = croppedBitmap.scale(croppedBitmap.width * 2, croppedBitmap.height * 2)

			// Make the cropped screenshot grayscale.
			val cvImage = Mat()
			Utils.bitmapToMat(resizedBitmap, cvImage)
			Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugDayForExtraRace_afterCrop.png", cvImage)

			// Thresh the grayscale cropped image to make it black and white.
			val bwImage = Mat()
			val threshold = sharedPreferences.getInt("threshold", 230)
			Imgproc.threshold(cvImage, bwImage, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugDayForExtraRace_afterThreshold.png", bwImage)

			// Create a InputImage object for Google's ML OCR.
			val resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
			Utils.matToBitmap(bwImage, resultBitmap)
			val inputImage: InputImage = InputImage.fromBitmap(resultBitmap, 0)

			// Use CountDownLatch to make the async operation synchronous.
			val latch = CountDownLatch(1)
			var mlkitFailed = false

			textRecognizer.process(inputImage)
				.addOnSuccessListener { text ->
					if (text.textBlocks.isNotEmpty()) {
						for (block in text.textBlocks) {
							try {
								game.printToLog(
									"[INFO] Detected Day Number for Extra Race with Google ML Kit: ${block.text}",
									tag = tag
								)
								result = block.text.toInt()
							} catch (_: NumberFormatException) {
							}
						}
					}
					latch.countDown()
				}
				.addOnFailureListener {
					game.printToLog(
						"[ERROR] Failed to do text detection via Google's ML Kit. Falling back to Tesseract.",
						tag = tag,
						isError = true
					)
					mlkitFailed = true
					latch.countDown()
				}

			// Wait for the async operation to complete.
			try {
				latch.await(5, TimeUnit.SECONDS)
			} catch (_: InterruptedException) {
				game.printToLog("[ERROR] Google ML Kit operation timed out", tag = tag, isError = true)
			}

			// Fallback to Tesseract if ML Kit failed or didn't find result.
			if (mlkitFailed || result == -1) {
				tessBaseAPI.setImage(resultBitmap)
				tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE

				try {
					val detectedText = tessBaseAPI.utF8Text.replace("%", "")
					game.printToLog("[INFO] Detected day for extra racing with Tesseract: $detectedText", tag = tag)
					val cleanedResult = detectedText.replace(Regex("[^0-9]"), "")
					result = cleanedResult.toInt()
				} catch (_: NumberFormatException) {
					game.printToLog(
						"[ERROR] Could not convert \"${
							tessBaseAPI.utF8Text.replace(
								"%",
								""
							)
						}\" to integer.", tag = tag, isError = true
					)
					result = -1
				} catch (e: Exception) {
					game.printToLog(
						"[ERROR] Cannot perform OCR using Tesseract: ${e.stackTraceToString()}",
						tag = tag,
						isError = true
					)
					result = -1
				}

				tessBaseAPI.clear()
			}

			cvImage.release()
			bwImage.release()
		}

		return result
	}

	/**
	 * Determine the amount of fans that the extra race will give only if it matches the double star prediction.
	 *
	 * @param extraRaceLocation Point object of the extra race's location.
	 * @param sourceBitmap Bitmap of the source screenshot.
	 * @param doubleStarPredictionBitmap Bitmap of the double star prediction template image.
	 * @param forceRacing Flag to allow the extra race to forcibly pass double star prediction check. Defaults to false.
	 * @return Number of fans to be gained from the extra race or -1 if not found as an object.
	 */
	fun determineExtraRaceFans(
		extraRaceLocation: Point,
		sourceBitmap: Bitmap,
		doubleStarPredictionBitmap: Bitmap,
		forceRacing: Boolean = false
	): RaceDetails {
		// Crop the source screenshot to show only the fan amount and the predictions.
		val croppedBitmap = if (isTablet) {
			createSafeBitmap(
				sourceBitmap,
				relX(extraRaceLocation.x, -(173 * 1.34).toInt()),
				relY(extraRaceLocation.y, -(106 * 1.34).toInt()),
				relWidth(220),
				relHeight(125),
				"determineExtraRaceFans prediction tablet"
			)
		} else {
			createSafeBitmap(
				sourceBitmap,
				relX(extraRaceLocation.x, -173),
				relY(extraRaceLocation.y, -106),
				relWidth(163),
				relHeight(96),
				"determineExtraRaceFans prediction phone"
			)
		}
		if (croppedBitmap == null) {
			game.printToLog(
				"[ERROR] Failed to create cropped bitmap for extra race prediction detection.",
				tag = tag,
				isError = true
			)
			return RaceDetails(-1, false)
		}

		val cvImage = Mat()
		Utils.bitmapToMat(croppedBitmap, cvImage)
		if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRacePrediction.png", cvImage)

		// Determine if the extra race has double star prediction.
		val (predictionCheck, _) = match(croppedBitmap, doubleStarPredictionBitmap, "race_extra_double_prediction")

		return if (forceRacing || predictionCheck) {
			if (debugMode && !forceRacing) game.printToLog(
				"[DEBUG] This race has double predictions. Now checking how many fans this race gives.",
				tag = tag
			)
			else if (debugMode) game.printToLog(
				"[DEBUG] Check for double predictions was skipped due to the force racing flag being enabled. Now checking how many fans this race gives.",
				tag = tag
			)

			// Crop the source screenshot to show only the fans.
			val croppedBitmap2 = if (isTablet) {
				createSafeBitmap(
					sourceBitmap,
					relX(extraRaceLocation.x, -(625 * 1.40).toInt()),
					relY(extraRaceLocation.y, -(75 * 1.34).toInt()),
					relWidth(320),
					relHeight(45),
					"determineExtraRaceFans fans tablet"
				)
			} else {
				createSafeBitmap(
					sourceBitmap,
					relX(extraRaceLocation.x, -625),
					relY(extraRaceLocation.y, -75),
					relWidth(250),
					relHeight(35),
					"determineExtraRaceFans fans phone"
				)
			}
			if (croppedBitmap2 == null) {
				game.printToLog(
					"[ERROR] Failed to create cropped bitmap for extra race fans detection.",
					tag = tag,
					isError = true
				)
				return RaceDetails(-1, predictionCheck)
			}

			// Make the cropped screenshot grayscale.
			Utils.bitmapToMat(croppedBitmap2, cvImage)
			Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRaceFans_afterCrop.png", cvImage)

			// Convert the Mat directly to Bitmap and then pass it to the text reader.
			var resultBitmap = createBitmap(cvImage.cols(), cvImage.rows())
			Utils.matToBitmap(cvImage, resultBitmap)

			// Thresh the grayscale cropped image to make it black and white.
			val bwImage = Mat()
			val threshold = sharedPreferences.getInt("threshold", 230)
			Imgproc.threshold(cvImage, bwImage, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugExtraRaceFans_afterThreshold.png", bwImage)

			resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
			Utils.matToBitmap(bwImage, resultBitmap)
			tessBaseAPI.setImage(resultBitmap)

			// Set the Page Segmentation Mode to '--psm 7' or "Treat the image as a single text line" according to https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method
			tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE

			var result = "empty!"
			try {
				// Finally, detect text on the cropped region.
				result = tessBaseAPI.utF8Text
			} catch (e: Exception) {
				game.printToLog(
					"[ERROR] Cannot perform OCR with Tesseract: ${e.stackTraceToString()}",
					tag = tag,
					isError = true
				)
			}

			tessBaseAPI.clear()
			cvImage.release()
			bwImage.release()

			// Format the string to be converted to an integer.
			game.printToLog("[INFO] Detected number of fans from Tesseract before formatting: $result", tag = tag)
			result = result
				.replace(",", "")
				.replace(".", "")
				.replace("+", "")
				.replace("-", "")
				.replace(">", "")
				.replace("<", "")
				.replace("(", "")
				.replace("人", "")
				.replace("ォ", "")
				.replace("fans", "").trim()

			try {
				Log.d(tag, "Converting $result to integer for fans")
				val cleanedResult = result.replace(Regex("[^0-9]"), "")
				RaceDetails(cleanedResult.toInt(), predictionCheck)
			} catch (_: NumberFormatException) {
				RaceDetails(-1, predictionCheck)
			}
		} else {
			Log.d(tag, "This race has no double prediction.")
			return RaceDetails(-1, false)
		}
	}

	/**
	 * Determine the number of skill points.
	 *
	 * @return Number of skill points or -1 if not found.
	 */
	fun determineSkillPoints(): Int {
		val (skillPointLocation, sourceBitmap) = findImage("skill_points", tries = 1)

		return if (skillPointLocation != null) {
			val croppedBitmap = if (isTablet) {
				createSafeBitmap(
					sourceBitmap,
					relX(skillPointLocation.x, -75),
					relY(skillPointLocation.y, 45),
					relWidth(150),
					relHeight(70),
					"determineSkillPoints tablet"
				)
			} else {
				createSafeBitmap(
					sourceBitmap,
					relX(skillPointLocation.x, -70),
					relY(skillPointLocation.y, 28),
					relWidth(135),
					relHeight(70),
					"determineSkillPoints phone"
				)
			}
			if (croppedBitmap == null) {
				game.printToLog(
					"[ERROR] Failed to create cropped bitmap for skill points detection.",
					tag = tag,
					isError = true
				)
				return -1
			}

			// Make the cropped screenshot grayscale.
			val cvImage = Mat()
			Utils.bitmapToMat(croppedBitmap, cvImage)
			Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugSkillPoints_afterCrop.png", cvImage)

			// Thresh the grayscale cropped image to make it black and white.
			val bwImage = Mat()
			val threshold = sharedPreferences.getInt("threshold", 230)
			Imgproc.threshold(cvImage, bwImage, threshold.toDouble(), 255.0, Imgproc.THRESH_BINARY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debugSkillPoints_afterThreshold.png", bwImage)

			// Create a InputImage object for Google's ML OCR.
			val resultBitmap = createBitmap(bwImage.cols(), bwImage.rows())
			Utils.matToBitmap(bwImage, resultBitmap)
			val inputImage: InputImage = InputImage.fromBitmap(resultBitmap, 0)

			// Use CountDownLatch to make the async operation synchronous.
			var result = ""
			val latch = CountDownLatch(1)
			var mlkitFailed = false

			textRecognizer.process(inputImage)
				.addOnSuccessListener { text ->
					if (text.textBlocks.isNotEmpty()) {
						for (block in text.textBlocks) {
							game.printToLog(
								"[INFO] Detected the number of skill points with Google ML Kit: ${block.text}",
								tag = tag
							)
							result = block.text
						}
					}
					latch.countDown()
				}
				.addOnFailureListener {
					game.printToLog(
						"[ERROR] Failed to do text detection via Google's ML Kit. Falling back to Tesseract.",
						tag = tag,
						isError = true
					)
					mlkitFailed = true
					latch.countDown()
				}

			// Wait for the async operation to complete.
			try {
				latch.await(5, TimeUnit.SECONDS)
			} catch (_: InterruptedException) {
				game.printToLog("[ERROR] Google ML Kit operation timed out", tag = tag, isError = true)
			}

			if (mlkitFailed || result == "") {
				tessBaseAPI.setImage(resultBitmap)

				// Set the Page Segmentation Mode to '--psm 7' or "Treat the image as a single text line" according to https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method
				tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE

				try {
					// Finally, detect text on the cropped region.
					result = tessBaseAPI.utF8Text
				} catch (e: Exception) {
					game.printToLog(
						"[ERROR] Cannot perform OCR with Tesseract: ${e.stackTraceToString()}",
						tag = tag,
						isError = true
					)
				}

				tessBaseAPI.clear()
			}

			cvImage.release()
			bwImage.release()

			game.printToLog("[INFO] Detected number of skill points before formatting: $result", tag = tag)
			try {
				Log.d(tag, "Converting $result to integer for skill points")
				val cleanedResult = result.replace(Regex("[^0-9]"), "")
				cleanedResult.toInt()
			} catch (_: NumberFormatException) {
				-1
			}
		} else {
			game.printToLog("[ERROR] Could not start the process of detecting skill points.", tag = tag, isError = true)
			-1
		}
	}

	/**
	 * Analyze the relationship bars on the Training screen for the currently selected training. Parameter is optional to allow for thread-safe operations.
	 *
	 * @param sourceBitmap Bitmap of the source image separately taken. Defaults to null.
	 *
	 * @return A list of the results for each relationship bar.
	 */
	fun analyzeRelationshipBars(sourceBitmap: Bitmap? = null): ArrayList<BarFillResult> {
		val customRegion =
			intArrayOf(displayWidth - (displayWidth / 3), 0, (displayWidth / 3), displayHeight - (displayHeight / 3))

		// Take a single screenshot first to avoid buffer overflow.
		val sourceBitmap = sourceBitmap ?: getSourceBitmap()

		var allStatBlocks = mutableListOf<Point>()

		val latch = CountDownLatch(6)

		// Create arrays to store results from each thread.
		val speedBlocks = arrayListOf<Point>()
		val staminaBlocks = arrayListOf<Point>()
		val powerBlocks = arrayListOf<Point>()
		val gutsBlocks = arrayListOf<Point>()
		val witBlocks = arrayListOf<Point>()
		val friendshipBlocks = arrayListOf<Point>()

		// Start parallel threads for each findAll call, passing the same source bitmap.
		Thread {
			speedBlocks.addAll(findAllWithBitmap("stat_speed_block", sourceBitmap, region = customRegion))
			latch.countDown()
		}.start()

		Thread {
			staminaBlocks.addAll(findAllWithBitmap("stat_stamina_block", sourceBitmap, region = customRegion))
			latch.countDown()
		}.start()

		Thread {
			powerBlocks.addAll(findAllWithBitmap("stat_power_block", sourceBitmap, region = customRegion))
			latch.countDown()
		}.start()

		Thread {
			gutsBlocks.addAll(findAllWithBitmap("stat_guts_block", sourceBitmap, region = customRegion))
			latch.countDown()
		}.start()

		Thread {
			witBlocks.addAll(findAllWithBitmap("stat_wit_block", sourceBitmap, region = customRegion))
			latch.countDown()
		}.start()

		Thread {
			friendshipBlocks.addAll(findAllWithBitmap("stat_friendship_block", sourceBitmap, region = customRegion))
			latch.countDown()
		}.start()

		// Wait for all threads to complete.
		try {
			latch.await(10, TimeUnit.SECONDS)
		} catch (_: InterruptedException) {
			game.printToLog("[ERROR] Parallel findAll operations timed out.", tag = tag, isError = true)
		}

		// Combine all results.
		allStatBlocks.addAll(speedBlocks)
		allStatBlocks.addAll(staminaBlocks)
		allStatBlocks.addAll(powerBlocks)
		allStatBlocks.addAll(gutsBlocks)
		allStatBlocks.addAll(witBlocks)
		allStatBlocks.addAll(friendshipBlocks)

		// Filter out duplicates based on exact coordinate matches.
		allStatBlocks = allStatBlocks.distinctBy { "${it.x},${it.y}" }.toMutableList()

		// Sort the combined stat blocks by ascending y-coordinate.
		allStatBlocks.sortBy { it.y }

		// Define HSV color ranges.
		val blueLower = Scalar(10.0, 150.0, 150.0)
		val blueUpper = Scalar(25.0, 255.0, 255.0)
		val greenLower = Scalar(40.0, 150.0, 150.0)
		val greenUpper = Scalar(80.0, 255.0, 255.0)
		val orangeLower = Scalar(100.0, 150.0, 150.0)
		val orangeUpper = Scalar(130.0, 255.0, 255.0)

		val (_, maxedTemplateBitmap) = getBitmaps("stat_maxed")
		val results = arrayListOf<BarFillResult>()

		for ((index, statBlock) in allStatBlocks.withIndex()) {
			if (debugMode) game.printToLog(
				"[DEBUG] Processing stat block #${index + 1} at position: (${statBlock.x}, ${statBlock.y})",
				tag = tag
			)

			val croppedBitmap = createSafeBitmap(
				sourceBitmap,
				relX(statBlock.x, -9),
				relY(statBlock.y, 107),
				111,
				13,
				"analyzeRelationshipBars stat block ${index + 1}"
			)
			if (croppedBitmap == null) {
				game.printToLog(
					"[ERROR] Failed to create cropped bitmap for stat block #${index + 1}.",
					tag = tag,
					isError = true
				)
				continue
			}

			val (isMaxed, _) = match(croppedBitmap, maxedTemplateBitmap!!, "stat_maxed")
			if (isMaxed) {
				// Skip if the relationship bar is already maxed.
				if (debugMode) game.printToLog("[DEBUG] Relationship bar #${index + 1} is full.", tag = tag)
				results.add(BarFillResult(100.0, 5, "orange"))
				continue
			}

			val barMat = Mat()
			Utils.bitmapToMat(croppedBitmap, barMat)

			// Convert to RGB and then to HSV for better color detection.
			val rgbMat = Mat()
			Imgproc.cvtColor(barMat, rgbMat, Imgproc.COLOR_BGR2RGB)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debug_relationshipBar${index + 1}AfterRGB.png", rgbMat)
			val hsvMat = Mat()
			Imgproc.cvtColor(rgbMat, hsvMat, Imgproc.COLOR_RGB2HSV)

			val blueMask = Mat()
			val greenMask = Mat()
			val orangeMask = Mat()

			// Count the pixels for each color.
			Core.inRange(hsvMat, blueLower, blueUpper, blueMask)
			Core.inRange(hsvMat, greenLower, greenUpper, greenMask)
			Core.inRange(hsvMat, orangeLower, orangeUpper, orangeMask)
			val bluePixels = Core.countNonZero(blueMask)
			val greenPixels = Core.countNonZero(greenMask)
			val orangePixels = Core.countNonZero(orangeMask)

			// Sum the colored pixels.
			val totalColoredPixels = bluePixels + greenPixels + orangePixels
			val totalPixels = barMat.rows() * barMat.cols()

			// Estimate the fill percentage based on the total colored pixels.
			val fillPercent = if (totalPixels > 0) {
				(totalColoredPixels.toDouble() / totalPixels.toDouble()) * 100.0
			} else 0.0

			// Estimate the filled segments (each segment is about 20% of the whole bar).
			val filledSegments = (fillPercent / 20).coerceAtMost(5.0).toInt()

			val dominantColor = when {
				orangePixels > greenPixels && orangePixels > bluePixels -> "orange"
				greenPixels > bluePixels -> "green"
				bluePixels > 0 -> "blue"
				else -> "none"
			}

			blueMask.release()
			greenMask.release()
			orangeMask.release()
			hsvMat.release()
			barMat.release()

			if (debugMode) game.printToLog(
				"[DEBUG] Relationship bar #${index + 1} is $fillPercent% filled with $filledSegments filled segments and the dominant color is $dominantColor",
				tag = tag
			)
			results.add(BarFillResult(fillPercent, filledSegments, dominantColor))
		}

		return results
	}

	/**
	 * Determines the preferred race distance based on aptitude levels (S, A, B) for each distance type on the Full Stats popup.
	 *
	 * This function analyzes the aptitude display for four race distances: Sprint, Mile, Medium, and Long.
	 * It uses template matching to detect S, A, and B aptitude levels and returns the distance with the
	 * highest aptitude found. The priority order is S > A > B, with S aptitude being returned immediately
	 * since it's the best possible outcome.
	 *
	 * @return The preferred distance (Sprint, Mile, Medium, or Long) or Medium as default if no aptitude is detected.
	 */
	fun determinePreferredDistance(): String {
		val (distanceLocation, sourceBitmap) = findImage("stat_distance", tries = 1, region = regionMiddle)
		if (distanceLocation == null) {
			game.printToLog(
				"[ERROR] Could not determine the preferred distance. Setting to Medium by default.",
				tag = tag,
				isError = true
			)
			return "Medium"
		}

		val (_, statAptitudeSTemplate) = getBitmaps("stat_aptitude_S")
		val (_, statAptitudeATemplate) = getBitmaps("stat_aptitude_A")
		val (_, statAptitudeBTemplate) = getBitmaps("stat_aptitude_B")

		val distances = listOf("Sprint", "Mile", "Medium", "Long")
		var bestAptitudeDistance = ""
		var bestAptitudeLevel = -1 // -1 = none, 0 = B, 1 = A, 2 = S

		for (i in 0 until 4) {
			val distance = distances[i]
			val croppedBitmap = createSafeBitmap(
				sourceBitmap,
				relX(distanceLocation.x, 108 + (i * 190)),
				relY(distanceLocation.y, -25),
				176,
				52,
				"determinePreferredDistance distance $distance"
			)
			if (croppedBitmap == null) {
				game.printToLog(
					"[ERROR] Failed to create cropped bitmap for distance $distance.",
					tag = tag,
					isError = true
				)
				continue
			}

			when {
				match(croppedBitmap, statAptitudeSTemplate!!, "stat_aptitude_S").first -> {
					// S aptitude found - this is the best possible, return immediately.
					return distance
				}

				bestAptitudeLevel < 1 && match(croppedBitmap, statAptitudeATemplate!!, "stat_aptitude_A").first -> {
					// A aptitude found (pick the leftmost aptitude) - better than B, but keep looking for S.
					bestAptitudeDistance = distance
					bestAptitudeLevel = 1
				}

				bestAptitudeLevel < 0 && match(croppedBitmap, statAptitudeBTemplate!!, "stat_aptitude_B").first -> {
					// B aptitude found - only use if no A aptitude found yet.
					bestAptitudeDistance = distance
					bestAptitudeLevel = 0
				}
			}
		}

		return bestAptitudeDistance.ifEmpty {
			game.printToLog(
				"[WARNING] Could not determine the preferred distance with at least B aptitude. Setting to Medium by default.",
				tag = tag,
				isError = true
			)
			"Medium"
		}
	}

	/**
	 * Reads the 5 stat values on the Main screen.
	 *
	 * @return The mapping of all 5 stats names to their respective integer values.
	 */
	fun determineStatValues(statValueMapping: MutableMap<String, Int>): MutableMap<String, Int> {
		val (skillPointsLocation, sourceBitmap) = findImage("skill_points")

		if (skillPointsLocation != null) {
			// Process all stats at once using the mapping
			statValueMapping.forEach { (statName, _) ->
				val croppedBitmap = when (statName) {
					"Speed" -> createSafeBitmap(
						sourceBitmap,
						relX(skillPointsLocation.x, -862),
						relY(skillPointsLocation.y, 25),
						relWidth(98),
						relHeight(42),
						"determineStatValues Speed stat"
					)

					"Stamina" -> createSafeBitmap(
						sourceBitmap,
						relX(skillPointsLocation.x, -862 + 170),
						relY(skillPointsLocation.y, 25),
						relWidth(98),
						relHeight(42),
						"determineStatValues Stamina stat"
					)

					"Power" -> createSafeBitmap(
						sourceBitmap,
						relX(skillPointsLocation.x, -862 + 170 * 2),
						relY(skillPointsLocation.y, 25),
						relWidth(98),
						relHeight(42),
						"determineStatValues Power stat"
					)

					"Guts" -> createSafeBitmap(
						sourceBitmap,
						relX(skillPointsLocation.x, -862 + 170 * 3),
						relY(skillPointsLocation.y, 25),
						relWidth(98),
						relHeight(42),
						"determineStatValues Guts stat"
					)

					"Wit" -> createSafeBitmap(
						sourceBitmap,
						relX(skillPointsLocation.x, -862 + 170 * 4),
						relY(skillPointsLocation.y, 25),
						relWidth(98),
						relHeight(42),
						"determineStatValues Wit stat"
					)

					else -> {
						game.printToLog(
							"[ERROR] determineStatValue() received an incorrect stat name of $statName.",
							tag = tag,
							isError = true
						)
						return@forEach
					}
				}
				if (croppedBitmap == null) {
					game.printToLog(
						"[ERROR] Failed to create cropped bitmap for reading $statName stat value.",
						tag = tag,
						isError = true
					)
					statValueMapping[statName] = -1
					return@forEach
				}

				// Make the cropped screenshot grayscale.
				val cvImage = Mat()
				Utils.bitmapToMat(croppedBitmap, cvImage)
				Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
				if (debugMode) Imgcodecs.imwrite("$matchFilePath/debug${statName}StatValue_afterCrop.png", cvImage)

				val resultBitmap = createBitmap(cvImage.cols(), cvImage.rows())
				Utils.matToBitmap(cvImage, resultBitmap)
				tessBaseAPI.setImage(resultBitmap)

				// Set the Page Segmentation Mode to '--psm 7' or "Treat the image as a single text line" according to https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method
				tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE

				var result = "empty!"
				try {
					// Finally, detect text on the cropped region.
					result = tessBaseAPI.utF8Text
				} catch (e: Exception) {
					game.printToLog(
						"[ERROR] Cannot perform OCR with Tesseract: ${e.stackTraceToString()}",
						tag = tag,
						isError = true
					)
				}

				tessBaseAPI.clear()
				cvImage.release()

				game.printToLog(
					"[INFO] Detected number of stats for $statName from Tesseract before formatting: $result",
					tag = tag
				)
				if (result.lowercase().contains("max") || result.lowercase().contains("ax")) {
					game.printToLog("[INFO] $statName seems to be maxed out. Setting it to 1200.", tag = tag)
					statValueMapping[statName] = 1200
				} else {
					try {
						Log.d(tag, "Converting $result to integer for $statName stat value")
						val cleanedResult = result.replace(Regex("[^0-9]"), "")
						statValueMapping[statName] = cleanedResult.toInt()
					} catch (_: NumberFormatException) {
						statValueMapping[statName] = -1
					}
				}
			}
		} else {
			game.printToLog("[ERROR] Could not start the process of detecting stat values.", tag = tag, isError = true)
		}

		return statValueMapping
	}

	/**
	 * Performs OCR on the date region of the game screen to extract the current date string.
	 *
	 * @return The detected date string from the game screen, or empty string if detection fails.
	 */
	fun determineDayNumber(): String {
		val (energyLocation, sourceBitmap) = findImage("energy")
		var result = ""
		if (energyLocation != null) {
			val croppedBitmap = createSafeBitmap(
				sourceBitmap,
				relX(energyLocation.x, -268),
				relY(energyLocation.y, -180),
				relWidth(308),
				relHeight(35),
				"determineDayNumber"
			)
			if (croppedBitmap == null) {
				game.printToLog(
					"[ERROR] Failed to create cropped bitmap for day number detection.",
					tag = tag,
					isError = true
				)
				return ""
			}

			// Make the cropped screenshot grayscale.
			val cvImage = Mat()
			Utils.bitmapToMat(croppedBitmap, cvImage)
			Imgproc.cvtColor(cvImage, cvImage, Imgproc.COLOR_BGR2GRAY)
			if (debugMode) Imgcodecs.imwrite("$matchFilePath/debug_dateString_afterCrop.png", cvImage)

			// Create a InputImage object for Google's ML OCR.
			val resultBitmap = createBitmap(cvImage.cols(), cvImage.rows())
			Utils.matToBitmap(cvImage, resultBitmap)
			val inputImage: InputImage = InputImage.fromBitmap(resultBitmap, 0)

			// Use CountDownLatch to make the async operation synchronous.
			val latch = CountDownLatch(1)
			var mlkitFailed = false

			textRecognizer.process(inputImage)
				.addOnSuccessListener { text ->
					if (text.textBlocks.isNotEmpty()) {
						for (block in text.textBlocks) {
							game.printToLog("[INFO] Detected the date with Google ML Kit: ${block.text}", tag = tag)
							result = block.text
						}
					}
					latch.countDown()
				}
				.addOnFailureListener {
					game.printToLog(
						"[ERROR] Failed to do text detection via Google's ML Kit. Falling back to Tesseract.",
						tag = tag,
						isError = true
					)
					mlkitFailed = true
					latch.countDown()
				}

			// Wait for the async operation to complete.
			try {
				latch.await(5, TimeUnit.SECONDS)
			} catch (_: InterruptedException) {
				game.printToLog("[ERROR] Google ML Kit operation timed out", tag = tag, isError = true)
			}

			// Fallback to Tesseract if ML Kit failed or didn't find result.
			if (mlkitFailed || result == "") {
				tessBaseAPI.setImage(resultBitmap)
				tessBaseAPI.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE

				try {
					result = tessBaseAPI.utF8Text
					game.printToLog("[INFO] Detected date with Tesseract: $result", tag = tag)
				} catch (e: Exception) {
					game.printToLog(
						"[ERROR] Cannot perform OCR using Tesseract: ${e.stackTraceToString()}",
						tag = tag,
						isError = true
					)
					result = ""
				}

				tessBaseAPI.clear()
			}

			if (debugMode) {
				game.printToLog("[DEBUG] Date string detected to be at \"$result\".")
			} else {
				Log.d(tag, "Date string detected to be at \"$result\".")
			}
		} else {
			game.printToLog(
				"[ERROR] Could not start the process of detecting the date string.",
				tag = tag,
				isError = true
			)
		}

		return result
	}

	/**
	 * Initialize Tesseract for future OCR operations. Make sure to put your .traineddata inside the root of the /assets/ folder.
	 */
	private fun initTesseract() {
		val externalFilesDir: File? = myContext.getExternalFilesDir(null)
		val tempDirectory: String = externalFilesDir?.absolutePath + "/tesseract/tessdata/"
		val newTempDirectory = File(tempDirectory)

		// If the /files/temp/ folder does not exist, create it.
		if (!newTempDirectory.exists()) {
			val successfullyCreated: Boolean = newTempDirectory.mkdirs()

			// If the folder was not able to be created for some reason, log the error and stop the MediaProjection Service.
			if (!successfullyCreated) {
				game.printToLog(
					"[ERROR] Failed to create the /files/tesseract/tessdata/ folder.",
					tag = tag,
					isError = true
				)
			} else {
				game.printToLog("[INFO] Successfully created /files/tesseract/tessdata/ folder.", tag = tag)
			}
		} else {
			game.printToLog("[INFO] /files/tesseract/tessdata/ folder already exists.", tag = tag)
		}

		// If the traineddata is not in the application folder, copy it there from assets.
		tesseractLanguages.forEach { lang ->
			val trainedDataPath = File(tempDirectory, "$lang.traineddata")
			if (!trainedDataPath.exists()) {
				try {
					game.printToLog("[INFO] Starting Tesseract initialization.", tag = tag)
					val input = myContext.assets.open("$lang.traineddata")

					val output = FileOutputStream("$tempDirectory/$lang.traineddata")

					val buffer = ByteArray(1024)
					var read: Int
					while (input.read(buffer).also { read = it } != -1) {
						output.write(buffer, 0, read)
					}

					input.close()
					output.flush()
					output.close()
					game.printToLog("[INFO] Finished Tesseract initialization.", tag = tag)
				} catch (e: IOException) {
					game.printToLog("[ERROR] IO EXCEPTION: ${e.stackTraceToString()}", tag = tag, isError = true)
				}
			}
		}
	}

	/**
	 * Determines the stat gain values from training. Parameters are optional to allow for thread-safe operations.
	 *
	 * This function uses template matching to find individual digits and the "+" symbol in the
	 * stat gain area of the training screen. It processes templates for digits 0-9 and the "+"
	 * symbol, then constructs the final integer value by analyzing the spatial arrangement
	 * of detected matches.
	 *
	 * @param trainingName Name of the currently selected training to determine which stats to read.
	 * @param sourceBitmap Bitmap of the source image separately taken. Defaults to null.
	 * @param skillPointsLocation Point location of the template image separately taken. Defaults to null.
	 *
	 * @return Array of 5 detected stat gain values as integers, or -1 for failed detections.
	 */
	fun determineStatGainFromTraining(
		trainingName: String,
		sourceBitmap: Bitmap? = null,
		skillPointsLocation: Point? = null
	): IntArray {
		val templates = listOf("+", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9")
		val statNames = listOf("Speed", "Stamina", "Power", "Guts", "Wit")
		// Define a mapping of training types to their stat indices
		val trainingToStatIndices = mapOf(
			"Speed" to listOf(0, 2),
			"Stamina" to listOf(1, 3),
			"Power" to listOf(1, 2),
			"Guts" to listOf(0, 2, 3),
			"Wit" to listOf(0, 4)
		)

		val (skillPointsLocation, sourceBitmap) = if (sourceBitmap == null && skillPointsLocation == null) {
			findImage("skill_points")
		} else {
			Pair(skillPointsLocation, sourceBitmap)
		}

		val threadSafeResults = IntArray(5)

		if (skillPointsLocation != null) {
			// Pre-load all template bitmaps to avoid thread contention
			val templateBitmaps = mutableMapOf<String, Bitmap?>()
			for (templateName in templates) {
				myContext.assets?.open("images/$templateName.png").use { inputStream ->
					templateBitmaps[templateName] = BitmapFactory.decodeStream(inputStream)
				}
			}

			// Process all stats in parallel using threads.
			val statLatch = CountDownLatch(5)
			for (i in 0 until 5) {
				Thread {
					try {
						// Stop the Thread early if the selected Training would not offer stats for the stat to be checked.
						// Speed gives Speed and Power
						// Stamina gives Stamina and Guts
						// Power gives Stamina and Power
						// Guts gives Speed, Power and Guts
						// Wits gives Speed and Wits
						val validIndices = trainingToStatIndices[trainingName] ?: return@Thread
						if (i !in validIndices) return@Thread

						val statName = statNames[i]
						val xOffset = i * 180 // All stats are evenly spaced at 180 pixel intervals.

						val croppedBitmap = createSafeBitmap(
							sourceBitmap!!,
							relX(skillPointsLocation.x, -934 + xOffset),
							relY(skillPointsLocation.y, -103),
							relWidth(150),
							relHeight(82),
							"determineStatGainFromTraining $statName"
						)
						if (croppedBitmap == null) {
							game.printToLog(
								"[ERROR] [$trainingName] Failed to create cropped bitmap for $statName stat gain detection.",
								tag = tag,
								isError = true
							)
							threadSafeResults[i] = 0
							statLatch.countDown()
							return@Thread
						}

						// Convert to Mat and then turn it to grayscale.
						val sourceMat = Mat()
						Utils.bitmapToMat(croppedBitmap, sourceMat)
						val sourceGray = Mat()
						Imgproc.cvtColor(sourceMat, sourceGray, Imgproc.COLOR_BGR2GRAY)

						val workingMat = Mat()
						sourceGray.copyTo(workingMat)

						var matchResults = mutableMapOf<String, MutableList<Point>>()
						templates.forEach { template ->
							matchResults[template] = mutableListOf()
						}

						for (templateName in templates) {
							val templateBitmap = templateBitmaps[templateName]
							if (templateBitmap != null) {
								matchResults = processStatGainTemplateWithTransparency(
									statName,
									trainingName,
									templateName,
									templateBitmap,
									workingMat,
									matchResults
								)
							} else {
								game.printToLog(
									"[ERROR] [$trainingName] Could not load template \"$templateName\".",
									tag = tag,
									isError = true
								)
							}
						}

						// Analyze results and construct the final integer value for this region.
						val finalValue = constructIntegerFromMatches(statName, trainingName, matchResults)
						threadSafeResults[i] = finalValue
						game.printToLog(
							"[INFO] [$trainingName - $statName] region final constructed value: $finalValue.",
							tag = tag
						)

						// Draw final visualization with all matches for this region.
						if (debugMode) {
							val resultMat = Mat()
							Utils.bitmapToMat(croppedBitmap, resultMat)
							templates.forEachIndexed { index, templateName ->
								matchResults[templateName]?.forEach { point ->
									val templateBitmap = templateBitmaps[templateName]
									if (templateBitmap != null) {
										val templateWidth = templateBitmap.width
										val templateHeight = templateBitmap.height

										// Calculate the bounding box coordinates.
										val x1 = (point.x - templateWidth / 2).toInt()
										val y1 = (point.y - templateHeight / 2).toInt()
										val x2 = (point.x + templateWidth / 2).toInt()
										val y2 = (point.y + templateHeight / 2).toInt()

										// Draw the bounding box.
										Imgproc.rectangle(
											resultMat,
											Point(x1.toDouble(), y1.toDouble()),
											Point(x2.toDouble(), y2.toDouble()),
											Scalar(0.0, 0.0, 0.0),
											2
										)

										// Add text label.
										Imgproc.putText(
											resultMat,
											templateName,
											Point(point.x, point.y),
											Imgproc.FONT_HERSHEY_SIMPLEX,
											0.5,
											Scalar(0.0, 0.0, 0.0),
											1
										)
									}
								}
							}

							Imgcodecs.imwrite(
								"$matchFilePath/debug_trainingStatGain_${trainingName}_${statNames[i]}_thread${i + 1}.png",
								resultMat
							)
						}

						sourceMat.release()
						sourceGray.release()
						workingMat.release()
					} catch (e: Exception) {
						game.printToLog(
							"[ERROR] [$trainingName] Error processing stat ${statNames[i]}: ${e.stackTraceToString()}",
							tag = tag,
							isError = true
						)
						threadSafeResults[i] = 0
					} finally {
						statLatch.countDown()
					}
				}.start()
			}

			// Wait for all threads to complete.
			try {
				statLatch.await(30, TimeUnit.SECONDS)
			} catch (_: InterruptedException) {
				game.printToLog("[ERROR] [$trainingName] Stat processing timed out", tag = tag, isError = true)
			}

			game.printToLog(
				"[INFO] [$trainingName] All 5 stat regions processed. Results: ${threadSafeResults.contentToString()}",
				tag = tag
			)
		} else {
			game.printToLog(
				"[ERROR] [$trainingName] Could not find the skill points location to start determining stat gains.",
				tag = tag,
				isError = true
			)
		}

		return threadSafeResults
	}

	/**
	 * Processes a single template with transparency to find all valid matches in the working matrix
	 * using a non-maximum-suppression loop over a single matchTemplate result map.
	 *
	 * Validation criteria:
	 *  - Pixel match ratio (with tolerance) on non-transparent pixels only.
	 *  - Correlation coefficient computed with a mask over non-transparent pixels only.
	 *
	 * @param statName Name of the stat being processed for the current `trainingName` (logging/debugging).
	 * @param trainingName Name of the training category being processed (logging/debugging).
	 * @param templateName Name of the template being processed (logging/debugging).
	 * @param templateBitmap Template bitmap (must be 4-channel RGBA with transparency).
	 * @param workingMat Grayscale source image to search in (1-channel Mat). If not, it will be converted.
	 * @param matchResults Output map of detections by template name; points are centers of detected boxes.
	 *
	 * @return Updated matchResults map with all valid matches for this template.
	 */
	private fun processStatGainTemplateWithTransparency(
		statName: String,
		trainingName: String,
		templateName: String,
		templateBitmap: Bitmap,
		workingMat: Mat,
		matchResults: MutableMap<String, MutableList<Point>>
	): MutableMap<String, MutableList<Point>> {

		// Tunables (tested on dynamic backgrounds)
		val minMatchConfidence = 0.95
		val minPixelMatchRatio = 0.9
		val minPixelCorrelation = 0.85
		val pixelTolerance = 25.0   // intensity tolerance [0..255] for "equal" pixels

		// Ensure output list exists for this template
		val resultsForTemplate = matchResults.getOrPut(templateName) { mutableListOf() }

		// Convert template Bitmap -> Mat
		val templateMat = Mat()
		val templateGray = Mat()
		Utils.bitmapToMat(templateBitmap, templateMat, true)

		// Require transparency (4 channels expected)
		if (templateMat.channels() != 4) {
			Log.e(
				tag,
				"[ERROR] [$trainingName - $statName] Template \"$templateName\" must have transparency (4 channels)."
			)
			templateMat.release()
			templateGray.release()
			return matchResults
		}

		// Convert template to grayscale.
		// RGBA input (Android default) is `Imgproc.COLOR_BGRA2GRAY`. But we use Imgproc.COLOR_BGR2GRAY instead.
		Imgproc.cvtColor(templateMat, templateGray, Imgproc.COLOR_BGR2GRAY)

		// Extract alpha and build a binary mask of valid (non-transparent) pixels
		val splitChannels = ArrayList<Mat>(4)
		Core.split(templateMat, splitChannels)
		val alphaMask = splitChannels[3] // 4th channel is alpha in both RGBA and BGRA
		val validPixels = Mat()
		Core.compare(alphaMask, Scalar(200.0), validPixels, Core.CMP_GT) // 255 where alpha > 0

		// Transparency ratio sanity check
		val nonZeroAlpha = Core.countNonZero(alphaMask)
		val totalPixels = alphaMask.rows() * alphaMask.cols()
		val transparencyRatio = nonZeroAlpha.toDouble() / totalPixels
		if (transparencyRatio < 0.10) {
			Log.w(
				tag,
				"[DEBUG] [$trainingName - $statName] Template \"$templateName\" appears to be mostly transparent; skipping."
			)
			// Cleanup
			splitChannels.forEach { it.release() }
			validPixels.release()
			alphaMask.release()
			templateMat.release()
			templateGray.release()
			return matchResults
		}
		val maskNonZeroCount = nonZeroAlpha // used for pixel ratio denominator

		// Ensure workingMat is grayscale (1-channel)
		val srcGray: Mat
		var releaseSrcGray = false
		if (workingMat.channels() == 1) {
			srcGray = workingMat
		} else {
			srcGray = Mat()
			Imgproc.cvtColor(workingMat, srcGray, Imgproc.COLOR_BGR2GRAY)
			releaseSrcGray = true
		}

		// Perform one masked template match across the full image
		val result = Mat()
		Imgproc.matchTemplate(srcGray, templateGray, result, Imgproc.TM_CCORR_NORMED, alphaMask)

		// For debug visualization, draw boxes on a copy (optional)
		val debugViz: Mat? = if (debugMode) srcGray.clone() else null

		val w = templateGray.cols()
		val h = templateGray.rows()
		var iterations = 0

		while (true) {
			val mmr = Core.minMaxLoc(result)
			val matchValConfidence = mmr.maxVal
			if (matchValConfidence < minMatchConfidence) {
				Log.d(
					tag,
					"[DEBUG] [$trainingName - $statName] \"$templateName\" - stopping: next peak $matchValConfidence (matchValConfidence) < $minMatchConfidence (minMatchConfidence)"
				)
				break
			}

			val x = mmr.maxLoc.x.toInt()
			val y = mmr.maxLoc.y.toInt()

			// Bounds check (should be valid by construction, but guard anyway)
			if (x < 0 || y < 0 || x + w > srcGray.cols() || y + h > srcGray.rows()) {
				// Suppress this invalid location in the result and continue
				val rx0 = kotlin.math.max(0, x - w + 1)
				val ry0 = kotlin.math.max(0, y - h + 1)
				val rx1 = kotlin.math.min(result.cols() - 1, x + w - 1)
				val ry1 = kotlin.math.min(result.rows() - 1, y + h - 1)
				val rw = kotlin.math.max(0, rx1 - rx0 + 1)
				val rh = kotlin.math.max(0, ry1 - ry0 + 1)
				if (rw > 0 && rh > 0) {
					result.submat(Rect(rx0, ry0, rw, rh)).setTo(Scalar(0.0))
				}
				continue
			}

			// Extract matched region from the source
			val matchedRegion = Mat(srcGray, Rect(x, y, w, h))

			// 1) Pixel-ratio test over non-transparent pixels only (with tolerance)
			val diff = Mat()
			// |region - template|
			Core.absdiff(matchedRegion, templateGray, diff)
			val maskedDiff = Mat()
			// only alpha>0 locations
			diff.copyTo(maskedDiff, validPixels)
			val eqMask = Mat()
			Imgproc.threshold(
				maskedDiff,
				eqMask,
				pixelTolerance,
				255.0,
				Imgproc.THRESH_BINARY_INV
			) // 255 where diff<=tol
			val matchingPixels = Core.countNonZero(eqMask)
			val pixelMatchRatio = if (maskNonZeroCount > 0) {
				matchingPixels.toDouble() / maskNonZeroCount.toDouble()
			} else 0.0
			val failedPixelMatchRatio = pixelMatchRatio < minPixelMatchRatio

			// 2) Correlation test over non-transparent pixels only
			val pixelCorrelation = maskedCorrelation(templateGray, matchedRegion, validPixels)
			val failedPixelCorrelation = pixelCorrelation < minPixelCorrelation

			if (!failedPixelMatchRatio && !failedPixelCorrelation) {
				val centerX = x + w / 2
				val centerY = y + h / 2

				// Per-template overlap check scaled by template size
				val tooClose = resultsForTemplate.any { p ->
					kotlin.math.abs(centerX - p.x) < (w * 0.5) && kotlin.math.abs(centerY - p.y) < (h * 0.5)
				}

				if (!tooClose) {
					resultsForTemplate.add(Point(centerX.toDouble(), centerY.toDouble()))
					Log.d(
						tag,
						"[DEBUG] [$trainingName - $statName] Valid match for \"$templateName\" at ($centerX, $centerY), matchValConfidence=$matchValConfidence (minMatchConfidence=%.2f), pixelMatchRatio=%.3f (minPixelMatchRatio=%.2f), pixelCorrelation=%.3f (min=%.2f)".format(
							minMatchConfidence,
							pixelMatchRatio,
							minPixelMatchRatio,
							pixelCorrelation,
							minPixelCorrelation
						)
					)
					if (debugViz != null) {
						Imgproc.rectangle(
							debugViz,
							Point(x.toDouble(), y.toDouble()),
							Point((x + w).toDouble(), (y + h).toDouble()),
							Scalar(255.0), 2
						)
					}
				} else {
					Log.d(
						tag,
						"[DEBUG] [$trainingName - $statName] \"$templateName\" near-duplicate suppressed at ($centerX, $centerY)."
					)
				}
			} else {
				Log.d(
					tag,
					"[DEBUG] [$trainingName - $statName] \"$templateName\" rejected at ($x, $y): matchValConfidence=%.3f (minMatchConfidence=%.2f), pixelMatchRatio=%.3f (minPixelMatchRatio=%.2f), pixelCorrelation=%.3f (minPixelCorrelation=%.2f)".format(
						matchValConfidence, minMatchConfidence,
						pixelMatchRatio, minPixelMatchRatio, pixelCorrelation, minPixelCorrelation
					)
				)
			}

			// Non-maximum suppression in the result map:
			// Zero-out all top-left positions whose boxes would overlap this detection.
			val rx0 = kotlin.math.max(0, x - w + 1)
			val ry0 = kotlin.math.max(0, y - h + 1)
			val rx1 = kotlin.math.min(result.cols() - 1, x + w - 1)
			val ry1 = kotlin.math.min(result.rows() - 1, y + h - 1)
			val rw = kotlin.math.max(0, rx1 - rx0 + 1)
			val rh = kotlin.math.max(0, ry1 - ry0 + 1)
			if (rw > 0 && rh > 0) {
				result.submat(Rect(rx0, ry0, rw, rh)).setTo(Scalar(0.0))
			}

			// Cleanup per-iteration temporaries
			matchedRegion.release()
			diff.release()
			maskedDiff.release()
			eqMask.release()

			iterations += 1
			if (!BotService.isRunning) throw InterruptedException()
			if (iterations > 100 || resultsForTemplate.size > 10) { // safety valves
				Log.d(
					tag,
					"[DEBUG] [$trainingName - $statName] \"$templateName\" stopping after $iterations iterations; found=${resultsForTemplate.size}"
				)
				break
			}
		}

		// Debug image write (after all detections)
		if (debugMode && debugViz != null) {
			Imgcodecs.imwrite(
				"$matchFilePath/debugTrainingStatGain_${trainingName}_${statName}_${templateName}.png",
				debugViz
			)
			debugViz.release()
		}

		// Cleanup
		result.release()
		if (releaseSrcGray) srcGray.release()
		splitChannels.forEachIndexed { idx, ch -> if (idx != 3) ch.release() } // release non-alpha channels
		validPixels.release()
		alphaMask.release()
		templateMat.release()
		templateGray.release()

		return matchResults
	}

	/**
	 * Compute Pearson correlation between two single-channel Mats over a mask of valid pixels.
	 * Returns a value in [-1, 1]. If either region is nearly constant under the mask, returns 0.0.
	 */
	private fun maskedCorrelation(a8u: Mat, b8u: Mat, mask: Mat): Double {
		require(a8u.rows() == b8u.rows() && a8u.cols() == b8u.cols()) { "maskedCorrelation: size mismatch" }
		require(a8u.type() == CvType.CV_8UC1 && b8u.type() == CvType.CV_8UC1) { "maskedCorrelation expects CV_8UC1 inputs" }
		require(mask.type() == CvType.CV_8UC1 && mask.rows() == a8u.rows() && mask.cols() == a8u.cols()) { "mask must be CV_8UC1 and same size" }

		val a32 = Mat()
		val b32 = Mat()
		a8u.convertTo(a32, CvType.CV_32F)
		b8u.convertTo(b32, CvType.CV_32F)

		val meanA = MatOfDouble()
		val stdA = MatOfDouble()
		val meanB = MatOfDouble()
		val stdB = MatOfDouble()

		Core.meanStdDev(a32, meanA, stdA, mask)
		Core.meanStdDev(b32, meanB, stdB, mask)

		val mA = meanA.toArray()[0]
		val sA = stdA.toArray()[0]
		val mB = meanB.toArray()[0]
		val sB = stdB.toArray()[0]

		val prod = Mat()
		Core.multiply(a32, b32, prod) // elementwise product
		val meanProd = Core.mean(prod, mask).`val`[0]

		val cov = meanProd - (mA * mB)
		val denom = sA * sB

		// Cleanup
		a32.release(); b32.release(); prod.release()
		meanA.release(); stdA.release(); meanB.release(); stdB.release()

		if (denom < 1e-6) return 0.0
		return (cov / denom).coerceIn(-1.0, 1.0)
	}

	/**
	 * Constructs the final integer value from matched template locations of numbers by analyzing spatial arrangement.
	 *
	 * The function is designed for OCR-like scenarios where individual character templates
	 * are matched separately and need to be reconstructed into a complete number.
	 *
	 * If matchResults contains: {"+" -> [(10, 20)], "1" -> [(15, 20)], "2" -> [(20, 20)]}, it returns: 12 (from string "+12").
	 *
	 * @param statName Name of the stat being processed for the current `trainingName` (logging/debugging).
	 * @param trainingName Name of the training category being processed (logging/debugging).
	 * @param matchResults Map of template names (e.g., "0", "1", "2", "+") to their match locations.
	 *
	 * @return The constructed integer value or -1 if it failed.
	 */
	private fun constructIntegerFromMatches(
		statName: String,
		trainingName: String,
		matchResults: Map<String, MutableList<Point>>
	): Int {
		// 1) Flatten and keep only valid glyphs ('0'..'9' and '+')
		data class Glyph(val ch: Char, val x: Double, val y: Double)

		val glyphs = mutableListOf<Glyph>()
		matchResults.forEach { (templateName, points) ->
			if (templateName.isNotEmpty()) {
				val ch = templateName[0]
				if (ch == '+' || ch.isDigit()) {
					points.forEach { p -> glyphs.add(Glyph(ch, p.x, p.y)) }
				}
			}
		}

		if (glyphs.isEmpty()) {
			if (debugMode) game.printToLog(
				"[WARNING] [$trainingName - $statName] No glyphs to construct integer value.",
				tag = tag
			)
			return 0
		}

		// 2) Sort by X (left->right) to reconstruct reading order
		glyphs.sortBy { it.x }

		// For visibility in logs
		val constructedString = glyphs.joinToString(separator = "") { it.ch.toString() }
		game.printToLog(
			"[INFO] [$trainingName - $statName] Constructed glyph sequence: \"$constructedString\".",
			tag = tag
		)
		if (debugMode) {
			game.printToLog(
				"[DEBUG] [$trainingName - $statName] Sorted glyphs: ${
					glyphs.joinToString { "${it.ch}@(${it.x.toInt()},${it.y.toInt()})" }
				}",
				tag = tag
			)
		}

		// Benign handling: if we saw a '+' but no digits at all, return 0 quietly.
		val hasPlus = glyphs.any { it.ch == '+' }
		val hasDigit = glyphs.any { it.ch.isDigit() }
		if (hasPlus && !hasDigit) {
			if (debugMode) game.printToLog(
				"[DEBUG] [$trainingName - $statName] Only '+' detected and no digits; returning 0.",
				tag = tag
			)
			return 0
		}

		// 3) Candidate A: from the first leading '+' take consecutive digits to its right
		fun candidateAfterLeadingPlus(): String? {
			val idx = glyphs.indexOfFirst { it.ch == '+' }
			if (idx == -1) return null
			// '+' must be leftmost in the intended token; if there are digits before it, ignore them here.
			val sb = StringBuilder()
			for (i in (idx + 1) until glyphs.size) {
				val ch = glyphs[i].ch
				when {
					ch.isDigit() -> sb.append(ch)
					ch == '+' -> continue   // tolerate extra '+' and keep scanning to the right
					else -> break
				}
			}
			return sb.toString().takeIf { it.isNotEmpty() }
		}

		// 4) Candidate B: longest consecutive run of digits anywhere
		fun longestDigitRun(): String? {
			var bestStart = -1
			var bestLen = 0
			var curStart = -1
			var curLen = 0
			for (i in glyphs.indices) {
				if (glyphs[i].ch.isDigit()) {
					if (curLen == 0) curStart = i
					curLen++
					if (curLen > bestLen) {
						bestLen = curLen
						bestStart = curStart
					}
				} else {
					curLen = 0
				}
			}
			return if (bestLen > 0) {
				buildString(bestLen) {
					for (i in bestStart until (bestStart + bestLen)) append(glyphs[i].ch)
				}
			} else null
		}

		// 5) Candidate C: just the digits (fallback)
		fun allDigits(): String? {
			val s = glyphs.asSequence().map { it.ch }.filter { it.isDigit() }.joinToString("")
			return s.ifEmpty { null }
		}

		val numericPart =
			candidateAfterLeadingPlus()
				?: longestDigitRun()
				?: allDigits()

		if (numericPart == null) {
			// No '+' and no usable digit run: this is still a benign failure in OCR context.
			if (debugMode) game.printToLog(
				"[DEBUG] [$trainingName - $statName] No digits found in \"$constructedString\"; returning 0.",
				tag = tag
			)
			return 0
		}

		val result = numericPart.toIntOrNull()
		return if (result != null) {
			if (debugMode) game.printToLog(
				"[DEBUG] [$trainingName - $statName] Parsed value: $result from \"$constructedString\" using \"$numericPart\".",
				tag = tag
			)
			result
		} else {
			// No exception thrown; keep logs clean and non-fatal.
			game.printToLog(
				"[WARNING] [$trainingName - $statName] \"$numericPart\" could not be parsed as Int from \"$constructedString\"; returning 0.",
				tag = tag
			)
			0
		}
	}

//	/**
//	 * Constructs the final integer value from matched template locations of numbers by analyzing spatial arrangement.
//	 *
//	 * The function is designed for OCR-like scenarios where individual character templates
//	 * are matched separately and need to be reconstructed into a complete number.
//	 *
//	 * If matchResults contains: {"+" -> [(10, 20)], "1" -> [(15, 20)], "2" -> [(20, 20)]}, it returns: 12 (from string "+12").
//	 *
//	 * @param matchResults Map of template names (e.g., "0", "1", "2", "+") to their match locations.
//	 *
//	 * @return The constructed integer value or -1 if it failed.
//	 */
//	private fun constructIntegerFromMatches(trainingName: String, matchResults: Map<String, MutableList<Point>>): Int {
//		// Collect all matches with their template names.
//		val allMatches = mutableListOf<Pair<String, Point>>()
//		matchResults.forEach { (templateName, points) ->
//			points.forEach { point ->
//				allMatches.add(Pair(templateName, point))
//			}
//		}
//
//		if (allMatches.isEmpty()) {
//			if (debugMode) game.printToLog("[WARNING] [$trainingName] No matches found to construct integer value.", tag = tag)
//			return 0
//		}
//
//		// Sort matches by x-coordinate (left to right).
//		allMatches.sortBy { it.second.x }
//		if (debugMode) game.printToLog("[DEBUG] [$trainingName] Sorted matches: ${allMatches.map { "${it.first}@(${it.second.x}, ${it.second.y})" }}", tag = tag)
//
//		// Construct the string representation and then validate the format: start with + and contain only digits after.
//		val constructedString = allMatches.joinToString("") { it.first }
//		game.printToLog("[INFO] [$trainingName] Constructed string: \"$constructedString\".", tag = tag)
//
//		// Extract the numeric part and convert to integer.
//		return try {
//			val numericPart = if (constructedString.startsWith("+") && constructedString.substring(1).isNotEmpty()) {
//				constructedString.substring(1)
//			} else {
//				constructedString
//			}
//
//			val result = numericPart.toInt()
//			if (debugMode) game.printToLog("[DEBUG] [$trainingName] Successfully constructed integer value: $result from \"$constructedString\".", tag = tag)
//			result
//		} catch (e: NumberFormatException) {
//			game.printToLog("[ERROR] [$trainingName] Could not convert \"$constructedString\" to integer: ${e.stackTraceToString()}", tag = tag, isError = true)
//			0
//		}
//	}

	/**
	 * Calculates the Pearson correlation coefficient between two arrays of pixel values.
	 *
	 * The Pearson correlation coefficient measures the linear correlation between two variables,
	 * ranging from -1 (perfect negative correlation) to +1 (perfect positive correlation).
	 * A value of 0 indicates no linear correlation.
	 *
	 * @param array1 First array of pixel values from the template image.
	 * @param array2 Second array of pixel values from the matched region.
	 * @return Correlation coefficient between -1.0 and +1.0, or 0.0 if arrays are invalid
	 */
	private fun calculateCorrelation(array1: DoubleArray, array2: DoubleArray): Double {
		if (array1.size != array2.size || array1.isEmpty()) {
			return 0.0
		}

		val n = array1.size
		val sum1 = array1.sum()
		val sum2 = array2.sum()
		val sum1Sq = array1.sumOf { it * it }
		val sum2Sq = array2.sumOf { it * it }
		val pSum = array1.zip(array2).sumOf { it.first * it.second }

		// Calculate the numerator: n*Σ(xy) - Σx*Σy
		val num = pSum - (sum1 * sum2 / n)
		// Calculate the denominator: sqrt((n*Σx² - (Σx)²) * (n*Σy² - (Σy)²))
		val den = sqrt((sum1Sq - sum1 * sum1 / n) * (sum2Sq - sum2 * sum2 / n))

		// Return the correlation coefficient, handling division by zero.
		return if (den == 0.0) 0.0 else num / den
	}

	/**
	 * Finds a unique file path by appending a counter if the original path already exists.
	 *
	 * @param directory The directory where the file should be saved.
	 * @param fileName The original name of the file without the extension.
	 * @param extension The file extension (e.g., "png").
	 * @return A unique, absolute path for the new file.
	 */
	private fun findUniqueFilePath(directory: String, fileName: String, extension: String): String {
		val originalFile = File(directory, "$fileName.$extension")

		// Best case: The original path is available. No loop needed.
		if (!originalFile.exists()) {
			return originalFile.absolutePath
		}

		// If the original exists, start searching for a unique name.
		var counter = 1
		var newFile: File
		do {
			val newFileName = "${fileName}_${counter}.${extension}"
			newFile = File(directory, newFileName)
			counter++
		} while (newFile.exists())

		return newFile.absolutePath
	}

	fun saveDebugImage(matchFilePath: String, fileName: String, debugMat: Mat) {
		val uniqueFilePath = findUniqueFilePath(matchFilePath, fileName, "png")
		Imgcodecs.imwrite(uniqueFilePath, debugMat)
	}
}