/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ksl.modeling.guidedpath.rules

import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.randomlySelect

/**
 * Chooses which idle transporter is sent to collect an entity.
 *
 * Dispatching is one of the two decisions that most affect how a guide path performs, and it is
 * the one most often varied in a study, so it is a replaceable rule rather than a fixed policy.
 *
 * A rule must choose from the candidates it is given, all of which are idle. It must not move,
 * allocate, or otherwise disturb anything: it decides only. And it must be a pure function of what
 * it is given, or take its randomness from a stream the model controls, or the run stops being
 * reproducible.
 */
fun interface GuidedTransporterAllocationRuleIfc {

    /**
     * @param network the guide path, for measuring distance along it
     * @param pickup where the transporter is wanted
     * @param candidates the idle transporters, never empty
     * @return the one to send, which must be one of the candidates
     */
    fun selectTransporter(
        network: GuidedPathNetwork,
        pickup: GuidedPathNetwork.Intersection,
        candidates: List<GuidedTransporter>
    ): GuidedTransporter
}

/**
 * Sends whichever idle transporter has the shortest journey to the pickup.
 *
 * The default, and the obvious policy: it minimises the empty running that a fleet does. Note that
 * distance here is distance *along the guide path*, not separation in space. On a one-way loop a
 * transporter standing a few feet past the pickup point may have to go all the way round, so
 * choosing by straight-line distance would routinely send the wrong one -- quietly, and with no
 * symptom other than a fleet that performs worse than it should.
 *
 * Ties go to the earlier transporter in the candidate list, which is declaration order, so the
 * choice is the same on every run.
 */
class ClosestByNetworkDistanceRule : GuidedTransporterAllocationRuleIfc {

    override fun selectTransporter(
        network: GuidedPathNetwork,
        pickup: GuidedPathNetwork.Intersection,
        candidates: List<GuidedTransporter>
    ): GuidedTransporter {
        var best = candidates.first()
        var bestDistance = Double.POSITIVE_INFINITY
        for (candidate in candidates) {
            val from = candidate.frontZone ?: continue
            val at = network.intersectionOf(from)
            val d = if (network.isReachable(at, pickup)) network.distance(at, pickup)
            else Double.POSITIVE_INFINITY
            if (d < bestDistance) {
                bestDistance = d
                best = candidate
            }
        }
        return best
    }

    override fun toString(): String = "ClosestByNetworkDistanceRule"
}

/**
 * Sends whichever idle transporter has the longest journey to the pickup.
 *
 * Rarely what a system should do, but useful as a contrast in a study: it shows how much of a
 * fleet's performance is due to dispatching rather than to the layout or the fleet size.
 */
class FurthestByNetworkDistanceRule : GuidedTransporterAllocationRuleIfc {

    override fun selectTransporter(
        network: GuidedPathNetwork,
        pickup: GuidedPathNetwork.Intersection,
        candidates: List<GuidedTransporter>
    ): GuidedTransporter {
        var best = candidates.first()
        var bestDistance = Double.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val from = candidate.frontZone ?: continue
            val at = network.intersectionOf(from)
            if (!network.isReachable(at, pickup)) continue
            val d = network.distance(at, pickup)
            if (d > bestDistance) {
                bestDistance = d
                best = candidate
            }
        }
        return best
    }

    override fun toString(): String = "FurthestByNetworkDistanceRule"
}

/**
 * Sends whichever idle transporter has been allocated fewest times, spreading work across a fleet.
 *
 * Useful where transporters wear or need charging and the study cares about even usage rather than
 * about minimising empty running.
 */
class LeastUsedTransporterRule : GuidedTransporterAllocationRuleIfc {

    override fun selectTransporter(
        network: GuidedPathNetwork,
        pickup: GuidedPathNetwork.Intersection,
        candidates: List<GuidedTransporter>
    ): GuidedTransporter = candidates.minByOrNull { it.numTimesSeized } ?: candidates.first()

    override fun toString(): String = "LeastUsedTransporterRule"
}

/**
 * Sends an idle transporter chosen at random.
 *
 * The stream is supplied rather than taken from a global source, so that the choice is under the
 * model's control and the run stays reproducible.
 *
 * @param stream the source of randomness for the choice
 */
class RandomTransporterRule(private val stream: RNStreamIfc) : GuidedTransporterAllocationRuleIfc {

    override fun selectTransporter(
        network: GuidedPathNetwork,
        pickup: GuidedPathNetwork.Intersection,
        candidates: List<GuidedTransporter>
    ): GuidedTransporter = candidates.randomlySelect(stream)

    override fun toString(): String = "RandomTransporterRule"
}
