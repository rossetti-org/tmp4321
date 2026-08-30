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

/**
 * Send the vehicle to a named staging point.
 *
 * Between [ReturnToHomeBaseDisposition] and [ParkInPlaceDisposition] in what it assumes: a home base
 * is per vehicle and a staging area is shared, so this is the rule for a fleet that should gather
 * somewhere central rather than disperse to its own corners. Whether that is better depends entirely
 * on where the work comes from, which is why it is a policy and not a default.
 *
 * The staging point must have room for every vehicle that may go there. A zone holds one vehicle, so
 * a staging *intersection* stages exactly one and the rest queue on the approach -- which is usually
 * not what was wanted and is exactly the configuration that quietly strangles a model. Stage on a
 * spur per vehicle, or accept that this is a rule about one parking space.
 */
class MoveToStagingDisposition(val locationName: String) : DispositionPolicyIfc {
    override fun disposition(vehicle: AgvVehicle): Disposition = Disposition.MoveTo(locationName)
    override fun toString(): String = "MoveToStagingDisposition($locationName)"
}
