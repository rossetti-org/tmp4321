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
package ksl.modeling.guidedpath

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  `G10`: the size of a report must not depend on the size of the guide path.
 *
 *  A thousand-zone network with occupancy collected automatically would register a thousand
 *  time-weighted responses, and every standard report, every CSV, and every output-database table
 *  would carry them whether or not anyone had asked. The result is not merely verbose: it makes a
 *  realistic network unusable for the ordinary case, which is somebody who wants to know how long
 *  parts took.
 *
 *  So the assertion here is exact rather than approximate. A ten-zone network and a network a
 *  hundred times larger must register **the same number** of responses with the detail switched
 *  off, and the difference when it is switched on must be exactly the responses asked for and
 *  nothing else.
 */
class ResponseCardinalityTest {

    /**
     *  A ring of `n` intersections joined by one-way links, so that zone count scales with `n` and
     *  every network built this way is legal.
     *
     *  @param zonesPerLink how finely each link is divided, which is what makes the zone count grow
     *   independently of the intersection count
     */
    private class Ring(
        parent: ModelElement,
        intersections: Int,
        zonesPerLink: Int,
        collectLinks: Boolean = false,
        collectZones: Boolean = false
    ) : ModelElement(parent, "Ring") {

        val network: GuidedPathNetwork = run {
            var b = GuidedPathNetwork.builder("Ring$intersections")
            for (i in 0 until intersections) {
                b = b.intersection("I$i", x = i.toDouble() * 10.0, y = 0.0)
            }
            for (i in 0 until intersections) {
                val next = (i + 1) % intersections
                b = b.link(
                    "L$i", "I$i", "I$next",
                    length = 12.0 * zonesPerLink, zoneLength = 12.0, beginDirection = 0.0
                )
            }
            b.build()
        }

        val system = GuidedPathTransportSystem(
            this, network,
            collectLinkStatistics = collectLinks,
            collectZoneStatistics = collectZones,
            name = "Sys"
        )
    }

    /** Every response and counter registered anywhere in the model. */
    private fun statisticNames(build: (Model) -> Unit): Set<String> {
        val m = Model("Cardinality")
        build(m)
        return (m.responses.map { it.name } + m.counters.map { it.name }).toSet()
    }

    @Test
    @DisplayName("A network a hundred times larger registers exactly the same statistics")
    fun cardinalityDoesNotGrowWithTheNetwork() {
        val small = statisticNames { Ring(it, intersections = 5, zonesPerLink = 2) }
        val large = statisticNames { Ring(it, intersections = 50, zonesPerLink = 20) }

        // Named rather than merely counted: two sets of the same size could still differ, and the
        // property being defended is that a bigger guide path changes nothing about the report.
        assertEquals(
            small, large,
            "a ten-zone and a thousand-zone network must register the same statistics with the " +
                    "detail off. Extra in the large one: ${large - small}"
        )
        assertTrue(
            small.none { it.contains(":L0:") },
            "no per-link statistic may be registered when link statistics were not asked for: $small"
        )
    }

    @Test
    @DisplayName("A thousand-zone network really is a thousand zones")
    fun theLargeNetworkIsActuallyLarge() {
        // Guards the test above from passing because both networks were small.
        val m = Model("SizeCheck")
        val ring = Ring(m, intersections = 50, zonesPerLink = 20)
        assertEquals(1050, ring.network.zones.size, "1000 link zones plus 50 intersections")
    }

    @Test
    @DisplayName("Asking for link statistics adds exactly two per link and one per intersection")
    fun linkStatisticsAddExactlyWhatWasAskedFor() {
        val off = statisticNames { Ring(it, intersections = 5, zonesPerLink = 4) }
        val on = statisticNames { Ring(it, intersections = 5, zonesPerLink = 4, collectLinks = true) }
        val added = on - off
        // Occupancy and utilization for each of the five links, and occupancy for each of the five
        // intersections. Nothing else: an opt-in that quietly brought other things with it would be
        // as surprising as no opt-in at all.
        assertEquals(15, added.size, "unexpected additions: $added")
        assertEquals(5, added.count { it.endsWith(":NumZonesOccupied") })
        assertEquals(5, added.count { it.endsWith(":Utilization") })
        assertEquals(5, added.count { it.endsWith(":IntersectionOccupied") })
    }

    @Test
    @DisplayName("Asking for zone statistics adds exactly one per zone")
    fun zoneStatisticsAddExactlyOnePerZone() {
        val off = statisticNames { Ring(it, intersections = 5, zonesPerLink = 4) }
        val on = statisticNames { Ring(it, intersections = 5, zonesPerLink = 4, collectZones = true) }
        val added = on - off
        // Twenty link zones and five intersection zones.
        assertEquals(25, added.size, "unexpected additions: $added")
        assertTrue(added.all { it.endsWith(":ZoneOccupied") }, "$added")
    }

    @Test
    @DisplayName("The system-level congestion statistics are always there")
    fun theAggregatesAreAlwaysRegistered() {
        // The other half of the trade: the detail is off by default, so the aggregates that answer
        // the first question anyone asks must not be, or the default is useless.
        val names = statisticNames { Ring(it, intersections = 5, zonesPerLink = 4) }
        for (suffix in listOf(
            ":NumTransportersMoving", ":NumTransportersBlocked", ":NumTransportersIdle",
            ":ZoneUtilization", ":NumDeadlocksDetected", ":NumObstructionsDetected",
            ":TransportTime", ":EmptyMoveTime", ":LoadedMoveTime", ":TransportBlockedTime",
            ":ZonesTraversedPerTransport", ":RouteLengthPerTransport",
            ":NumZoneTraversals", ":NumEventsScheduled", ":EventsPerZoneTraversal"
        )) {
            assertTrue(
                names.any { it.endsWith(suffix) },
                "$suffix must be registered whatever the network size, but was not in $names"
            )
        }
    }
}
