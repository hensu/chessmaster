// RatingKit — Chessmaster
// GPL-3.0-or-later
//
// Glicko-2 rating system, implemented from Mark Glickman's specification
// ("Example of the Glicko-2 system", glicko.net). Lichess uses the same
// system with tau = 0.75.

import Foundation

public struct Glicko2Rating: Sendable, Hashable, Codable {
    public var rating: Double
    public var deviation: Double
    public var volatility: Double

    public init(rating: Double = 1500, deviation: Double = 350, volatility: Double = 0.06) {
        self.rating = rating
        self.deviation = deviation
        self.volatility = volatility
    }
}

/// One game result against an opponent, from the player's perspective.
public struct Glicko2Result: Sendable {
    public let opponent: Glicko2Rating
    /// 1 = win, 0.5 = draw, 0 = loss.
    public let score: Double

    public init(opponent: Glicko2Rating, score: Double) {
        self.opponent = opponent
        self.score = score
    }
}

public enum Glicko2Calculator {
    /// Lichess's system constant.
    public static let defaultTau = 0.75
    private static let scale = 173.7178
    private static let epsilon = 0.000001

    /// One rating-period update (steps 2-8 of Glickman's paper).
    /// With no results, only the deviation grows (step 6 special case).
    public static func update(
        player: Glicko2Rating,
        results: [Glicko2Result],
        tau: Double = defaultTau
    ) -> Glicko2Rating {
        let mu = (player.rating - 1500) / scale
        let phi = player.deviation / scale

        guard !results.isEmpty else {
            let phiStar = sqrt(phi * phi + player.volatility * player.volatility)
            return Glicko2Rating(
                rating: player.rating,
                deviation: phiStar * scale,
                volatility: player.volatility
            )
        }

        // Step 3: estimated variance of the player's rating from game outcomes.
        var vInverse = 0.0
        var deltaSum = 0.0
        for result in results {
            let muJ = (result.opponent.rating - 1500) / scale
            let phiJ = result.opponent.deviation / scale
            let gJ = g(phiJ)
            let eJ = e(mu: mu, muJ: muJ, phiJ: phiJ)
            vInverse += gJ * gJ * eJ * (1 - eJ)
            deltaSum += gJ * (result.score - eJ)
        }
        let v = 1 / vInverse
        let delta = v * deltaSum

        // Step 5: new volatility via the illinois-style iteration.
        let a = log(player.volatility * player.volatility)
        func f(_ x: Double) -> Double {
            let ex = exp(x)
            let phi2 = phi * phi
            let num = ex * (delta * delta - phi2 - v - ex)
            let den = 2 * (phi2 + v + ex) * (phi2 + v + ex)
            return num / den - (x - a) / (tau * tau)
        }

        var A = a
        var B: Double
        if delta * delta > phi * phi + v {
            B = log(delta * delta - phi * phi - v)
        } else {
            var k = 1.0
            while f(a - k * tau) < 0 { k += 1 }
            B = a - k * tau
        }
        var fA = f(A)
        var fB = f(B)
        while abs(B - A) > epsilon {
            let C = A + (A - B) * fA / (fB - fA)
            let fC = f(C)
            if fC * fB <= 0 {
                A = B
                fA = fB
            } else {
                fA /= 2
            }
            B = C
            fB = fC
        }
        let newVolatility = exp(A / 2)

        // Steps 6-8: new deviation and rating.
        let phiStar = sqrt(phi * phi + newVolatility * newVolatility)
        let newPhi = 1 / sqrt(1 / (phiStar * phiStar) + 1 / v)
        let newMu = mu + newPhi * newPhi * deltaSum

        return Glicko2Rating(
            rating: newMu * scale + 1500,
            deviation: newPhi * scale,
            volatility: newVolatility
        )
    }

    private static func g(_ phi: Double) -> Double {
        1 / sqrt(1 + 3 * phi * phi / (Double.pi * Double.pi))
    }

    private static func e(mu: Double, muJ: Double, phiJ: Double) -> Double {
        1 / (1 + exp(-g(phiJ) * (mu - muJ)))
    }
}
