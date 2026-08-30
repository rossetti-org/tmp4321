package ksl.modeling.agv.policies

import ksl.modeling.agv.AgvVehicle

/** What a vehicle does with itself when the dispatcher has no work for it. */
sealed class Disposition {

    /** Stay exactly where it stopped. Cheap, and a hazard: a vehicle parked on the guide path
     *  denies that space to everyone else. */
    data object ParkInPlace : Disposition()

    /** Go to the vehicle's declared home base, if it has one. */
    data object ReturnToHomeBase : Disposition()

    /** Go somewhere named. */
    data class MoveTo(val locationName: String) : Disposition()

    // GoCharge arrives with the battery seam.
}

/**
 * Decides what an idle vehicle does with itself.
 *
 * Consulted only after the dispatcher has been given the chance to assign and has declined, so no
 * disposition policy can cause a vehicle to idle while work waits. That guarantee is structural --
 * the branch that consults this interface is unreachable until the dispatcher has passed -- rather
 * than a rule an implementer could break.
 */
fun interface DispositionPolicyIfc {
    fun disposition(vehicle: AgvVehicle): Disposition
}

/**
 * Send the vehicle home. The default, and the safe one.
 *
 * The passive subsystem learned this the hard way and the lesson transfers unchanged: with vehicles
 * left where they stop, the first delivery parks on a station, and if that station is the only way
 * off a spur then every later delivery stops at the mouth for the rest of the run. Nothing raises.
 * The model simply stops moving.
 */
class ReturnToHomeBaseDisposition : DispositionPolicyIfc {
    override fun disposition(vehicle: AgvVehicle): Disposition = Disposition.ReturnToHomeBase
    override fun toString(): String = "ReturnToHomeBaseDisposition"
}

/** Leave the vehicle where it stopped. See the warning on [ReturnToHomeBaseDisposition]. */
class ParkInPlaceDisposition : DispositionPolicyIfc {
    override fun disposition(vehicle: AgvVehicle): Disposition = Disposition.ParkInPlace
    override fun toString(): String = "ParkInPlaceDisposition"
}
