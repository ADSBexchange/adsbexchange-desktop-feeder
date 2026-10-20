package com.adsbexchange.feeder.service

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * What a laptop is running on, so the engine can pause: feeding on battery, stats uploads on a
 * metered connection (Settings > Feeding can keep either going). A stand-in in tests.
 */
interface PowerConditions {
    /** Running on its battery. False on a PC without one, and when Windows can't say. */
    fun onBattery(): Boolean

    /**
     * Windows treats the internet connection as metered: a phone hotspot, a capped plan, or a
     * connection the user set as metered. False when Windows can't say.
     */
    fun metered(): Boolean

    /** The real PC, through Windows' power status and its Network List Manager. */
    object Windows : PowerConditions {
        @Suppress("FunctionName")
        private interface Kernel32 : Library {
            fun GetSystemPowerStatus(status: Pointer): Boolean
        }

        private val kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }

        override fun onBattery(): Boolean = runCatching {
            // SYSTEM_POWER_STATUS: ACLineStatus (0 offline, 1 online, 255 unknown), then BatteryFlag
            // (128 no system battery, 255 unknown).
            val status = Memory(12)
            if (!kernel32.GetSystemPowerStatus(status)) return@runCatching false
            val acLine = status.getByte(0).toInt() and 0xff
            val battery = status.getByte(1).toInt() and 0xff
            acLine == 0 && battery != 128 && battery != 255
        }.getOrDefault(false)

        // netlistmgr.h
        private val CLSID_NETWORK_LIST_MANAGER = Guid.CLSID("{DCB00C01-570F-4A9B-8D69-199FDBA5723B}")
        private val IID_INETWORK_COST_MANAGER = Guid.IID("{DCB00008-570F-4A9B-8D69-199FDBA5723B}")
        private const val COST_FIXED = 0x2
        private const val COST_VARIABLE = 0x4
        private const val COST_OVER_DATA_LIMIT = 0x10000
        private const val COST_ROAMING = 0x40000
        private const val METERED = COST_FIXED or COST_VARIABLE or COST_OVER_DATA_LIMIT or COST_ROAMING

        /** COM is set up once per calling thread, and left set up: the engine's thread lives as long as the app. */
        private val comReady = ThreadLocal.withInitial {
            runCatching { Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED) }.isSuccess
        }

        /** INetworkCostManager: after IUnknown's three methods, GetCost(DWORD *pCost, NLM_SOCKADDR *pDest). */
        private class CostManager(p: Pointer) : Unknown(p) {
            fun cost(): Int? {
                val out = IntByReference()
                val hr = _invokeNativeInt(3, arrayOf(pointer, out, null))
                return if (hr == 0) out.value else null
            }
        }

        override fun metered(): Boolean = (connectionCost() ?: 0) and METERED != 0

        /** Windows' NLM_CONNECTION_COST flags for the internet connection (1: unrestricted); null if it can't say. */
        internal fun connectionCost(): Int? = runCatching {
            comReady.get()
            val ref = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(CLSID_NETWORK_LIST_MANAGER, null, WTypes.CLSCTX_ALL,
                IID_INETWORK_COST_MANAGER, ref)
            if (COMUtils.FAILED(hr) || ref.value == null) return@runCatching null
            val manager = CostManager(ref.value)
            try {
                manager.cost()
            } finally {
                manager.Release()
            }
        }.getOrNull()
    }
}
