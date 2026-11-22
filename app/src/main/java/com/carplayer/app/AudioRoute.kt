package com.carplayer.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Scelta dell'uscita audio.
 *
 * Con [AUTO] l'app non tocca niente: la musica segue l'instradamento di Android,
 * esattamente come i lettori del Play Store (AIMP & co.) che sull'autoradio si
 * sentono. Non si cambia la modalità di AudioManager e non si tocca lo
 * speakerphone: sono interventi globali che su queste radio azzittiscono il media.
 *
 * Solo se l'utente sceglie a mano un'uscita dalle impostazioni, il motore di
 * riproduzione riceve quel dispositivo come preferito.
 */
class AudioRoute(context: Context) {

    private val am = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** [AUTO] oppure l'id di un AudioDeviceInfo scelto a mano. */
    var manualSink = AUTO
        private set

    fun setSink(id: Int) {
        manualSink = if (id <= 0) AUTO else id
    }

    /** Uscita scelta a mano, oppure null per lasciare fare al sistema. */
    fun chosen(): AudioDeviceInfo? {
        if (manualSink == AUTO) return null
        return outputs().firstOrNull { it.id == manualSink }
    }

    /** Uscite disponibili: i soli sink, cioè i dispositivi su cui si può mandare l'audio. */
    fun outputs(): List<AudioDeviceInfo> {
        if (android.os.Build.VERSION.SDK_INT < 23) return emptyList()
        return try {
            val list = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return emptyList()
            list.filter { it.isSink }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Passa all'uscita successiva: Automatico, poi ogni dispositivo, poi di nuovo Automatico. */
    fun cycleSink(): String {
        val outs = outputs()
        val ids = ArrayList<Int>(outs.size + 1)
        ids.add(AUTO)
        for (d in outs) ids.add(d.id)
        val i = ids.indexOf(manualSink)
        manualSink = ids[if (i < 0) 0 else (i + 1) % ids.size]
        if (manualSink == AUTO) return "Automatico (sistema)"
        return outs.firstOrNull { it.id == manualSink }?.let { labelOf(it) } ?: "Automatico (sistema)"
    }

    fun labelOf(d: AudioDeviceInfo): String {
        val n = d.productName?.toString()?.trim().orEmpty()
        if (n.isNotEmpty() && n != "null") return n
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Cassa interna"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Capsula"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Cuffie"
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
            else -> "Uscita " + d.type
        }
    }

    companion object {
        /** Routing di sistema: l'app non tocca nulla (comportamento AIMP). */
        const val AUTO = 0
    }
}
