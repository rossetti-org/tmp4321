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
package ksl.modeling.agv

import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  A capacity a vehicle cannot honour is refused at construction rather than accepted and ignored.
 *
 *  `loadCapacity` used to be a settable property that **nothing read**. A modeller could ask for
 *  three and get one, with no error, no warning, and no way to tell: the vehicle holds a single
 *  assignment and builds its tour from one task, so the number changed nothing. That is a promise
 *  the subsystem does not keep, and it is the same family of defect as a statistic that is
 *  documented and never fed -- which this suite has already met once, in `PerCarryStatisticsTest`.
 *
 *  The honest behaviour, while the multi-load seam is unfilled, is to say so at construction. The
 *  message names the seam and offers the two things a modeller can actually do instead, because a
 *  refusal that leaves someone stuck is only half an answer.
 *
 *  Capacity is also now a **constructor parameter** rather than a `var`, which is what its two
 *  siblings already were: `lengthInZones` and `physicalLength` are physical properties of a vehicle,
 *  fixed when it is built, and capacity is the third. It was the odd one out for no reason.
 */
class LoadCapacityTest {

    private companion object {
        fun loop(name: String): GuidedPathNetwork = GuidedPathNetwork.builder(name)
            .link("Out", "A", "B", length = 100.0, zoneLength = 20.0, beginDirection = 0.0)
            .link("Back", "B", "A", length = 100.0, zoneLength = 20.0, beginDirection = 180.0)
            .build()
    }

    private class Shop(parent: ModelElement, capacity: Int) : ModelElement(parent, "Shop") {
        val network = loop("Net")

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val vehicle = AgvVehicle(
            agv, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart", loadCapacity = capacity
        )
    }

    @Test
    @DisplayName("a capacity of one is the default and is accepted")
    fun oneIsTheDefault() {
        val m = Model("Default")
        val shop = Shop(m, 1)
        assertEquals(1, shop.vehicle.loadCapacity)

        // And the default really is one, so no existing model has to say so.
        val plain = AgvVehicle(shop.agv, TransporterPlacement.At("B"), ConstantRV(10.0), name = "Plain")
        assertEquals(1, plain.loadCapacity)
    }

    @Test
    @DisplayName("a capacity above one is refused, and the message says what to do instead")
    fun aCapacityAboveOneIsRefused() {
        val m = Model("TooMany")
        val thrown = assertFailsWith<IllegalArgumentException> { Shop(m, 3) }
        val message = thrown.message ?: ""
        // The number asked for, so the reader knows which vehicle and which value.
        assertTrue("3" in message, message)
        // That it is unimplemented rather than invalid -- the distinction a modeller needs, because
        // one means "you asked for something wrong" and the other means "ask again later".
        assertTrue("not yet implemented" in message, message)
        // And a way forward, since a refusal that strands the reader is half an answer.
        assertTrue("larger fleet" in message, message)
    }

    @Test
    @DisplayName("a capacity below one is refused as the nonsense it is")
    fun aCapacityBelowOneIsRefused() {
        val m = Model("TooFew")
        val thrown = assertFailsWith<IllegalArgumentException> { Shop(m, 0) }
        assertTrue("at least one load" in (thrown.message ?: ""), thrown.message ?: "")
    }
}
