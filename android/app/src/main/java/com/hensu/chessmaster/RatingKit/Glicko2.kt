// RatingKit — Chessmaster (Android)
// GPL-3.0-or-later
//
// Glicko-2 rating system, implemented from Mark Glickman's specification
// ("Example of the Glicko-2 system", glicko.net). Lichess uses the same
// system with tau = 0.75.

package com.hensu.chessmaster

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
data class Glicko2Rating(
    var rating: Double = 1500.0,
    var deviation: Double = 350.0,
    var volatility: Double = 0.06,
)

/** One game result against an opponent, from the player's perspective. */
data class Glicko2Result(
    val opponent: Glicko2Rating,
    /** 1 = win, 0.5 = draw, 0 = loss. */
    val score: Double,
)

object Glicko2Calculator {
    /** Lichess's system constant. */
    const val defaultTau = 0.75
    private const val scale = 173.7178
    private const val epsilon = 0.000001

    /**
     * One rating-period update (steps 2-8 of Glickman's paper).
     * With no results, only the deviation grows (step 6 special case).
     */
    fun update(
        player: Glicko2Rating,
        results: List<Glicko2Result>,
        tau: Double = defaultTau,
    ): Glicko2Rating {
        val mu = (player.rating - 1500) / scale
        val phi = player.deviation / scale

        if (results.isEmpty()) {
            val phiStar = sqrt(phi * phi + player.volatility * player.volatility)
            return Glicko2Rating(
                rating = player.rating,
                deviation = phiStar * scale,
                volatility = player.volatility,
            )
        }

        // Step 3: estimated variance of the player's rating from game outcomes.
        var vInverse = 0.0
        var deltaSum = 0.0
        for (result in results) {
            val muJ = (result.opponent.rating - 1500) / scale
            val phiJ = result.opponent.deviation / scale
            val gJ = g(phiJ)
            val eJ = e(mu = mu, muJ = muJ, phiJ = phiJ)
            vInverse += gJ * gJ * eJ * (1 - eJ)
            deltaSum += gJ * (result.score - eJ)
        }
        val v = 1 / vInverse
        val delta = v * deltaSum

        // Step 5: new volatility via the illinois-style iteration.
        val a = ln(player.volatility * player.volatility)
        fun f(x: Double): Double {
            val ex = exp(x)
            val phi2 = phi * phi
            val num = ex * (delta * delta - phi2 - v - ex)
            val den = 2 * (phi2 + v + ex) * (phi2 + v + ex)
            return num / den - (x - a) / (tau * tau)
        }

        var A = a
        var B: Double
        if (delta * delta > phi * phi + v) {
            B = ln(delta * delta - phi * phi - v)
        } else {
            var k = 1.0
            while (f(a - k * tau) < 0) k += 1
            B = a - k * tau
        }
        var fA = f(A)
        var fB = f(B)
        while (abs(B - A) > epsilon) {
            val C = A + (A - B) * fA / (fB - fA)
            val fC = f(C)
            if (fC * fB <= 0) {
                A = B
                fA = fB
            } else {
                fA /= 2
            }
            B = C
            fB = fC
        }
        val newVolatility = exp(A / 2)

        // Steps 6-8: new deviation and rating.
        val phiStar = sqrt(phi * phi + newVolatility * newVolatility)
        val newPhi = 1 / sqrt(1 / (phiStar * phiStar) + 1 / v)
        val newMu = mu + newPhi * newPhi * deltaSum

        return Glicko2Rating(
            rating = newMu * scale + 1500,
            deviation = newPhi * scale,
            volatility = newVolatility,
        )
    }

    private fun g(phi: Double): Double = 1 / sqrt(1 + 3 * phi * phi / (Math.PI * Math.PI))

    private fun e(mu: Double, muJ: Double, phiJ: Double): Double = 1 / (1 + exp(-g(phiJ) * (mu - muJ)))
}
