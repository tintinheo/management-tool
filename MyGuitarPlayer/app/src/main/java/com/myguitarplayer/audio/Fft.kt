package com.myguitarplayer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT of a fixed power-of-two size. */
class Fft(val n: Int) {
    private val cosT = DoubleArray(n / 2) { cos(-2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(-2 * PI * it / n) }
    private val rev = IntArray(n)

    init {
        require(n >= 2 && n and (n - 1) == 0) { "n must be a power of two" }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]; val wi = sinT[k]
                    val l = j + half
                    val xr = re[l] * wr - im[l] * wi
                    val xi = re[l] * wi + im[l] * wr
                    re[l] = re[j] - xr; im[l] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                start += len
            }
            len *= 2
        }
    }
}
