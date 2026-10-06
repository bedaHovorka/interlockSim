/*
 * Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: pixel queries on rendered images
 */
package cz.vutbr.fit.interlockSim.testutil

import java.awt.Color
import java.awt.image.BufferedImage

/** Every pixel coordinate of [image], row by row. */
fun allPixels(image: BufferedImage): List<Pair<Int, Int>> =
	(0 until image.height).flatMap { y -> (0 until image.width).map { x -> x to y } }

/** The coordinates of the pixels of [image] whose RGB value equals [color] exactly. */
fun exactColorPixels(
	image: BufferedImage,
	color: Color
): List<Pair<Int, Int>> = allPixels(image).filter { (x, y) -> image.getRGB(x, y) == color.rgb }
