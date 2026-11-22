package com.carplayer.app

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Instradamento dell'audio.
 *
 * Sulle autoradio cinesi (RK3326, PX30, MTK...) il routing di sistema è già quello
 * giusto: i lettori installati dal Play Store (AIMP, Musicolet...) si limitano a
 * riprodurre su STREAM_MUSIC senza toccare AudioManager né un dispositivo preferito,
 * e dalle casse si sentono. CarPlayer invece forzava le casse interne con
 * setPreferredDevice + isSpeakerphoneOn: su questi HAL quella rotta non esiste e la
 * musica finiva nel nulla (play acceso, casse mute, "Altoparlante telefono" in tendina).
 *
 * Adesso:
 *  - [AUTO] non fa assolutamente nulla: si usa il routing di Android, come AIMP;
 *  - dalle impostazioni (ingranaggio) si può comunque scegliere a mano un'uscita,
 *    e solo in quel caso viene impostato il dispositivo preferito;
 *  - non si cambia più la modalità di AudioManager e non si tocca lo speakerphone,
 *    perché sono interventi globali che su queste radio azzittiscono il media.
 */
class AudioRoute(context: Context) {

    private val am = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var registered = false

    /**
     * Uscita scelta a mano: [AUTO] = routing di sistema, altrimenti l'id di un
     * AudioDeviceInfo. La scelta viene ricordata dal servizio.
     */
    var manualSink = AUTO
        private set

    private val devices: AudioDeviceCallback? =
        if (Build.VERSION.SDK_INT >= 23) object : AudioDeviceCallback() {
            // se colleghi o stacchi un'uscita, una scelta manuale viene riapplicata
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                main.post { apply() }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                main.post { apply() }
            }
        } else null

    /** Aggancia il player (subito dopo reset) e applica l'eventuale scelta manuale. */
    fun attach(p: MediaPlayer) {
        player = p
        register()
        apply()
    }

    private fun register() {
        if (registered) return
        val d = devices ?: return
        try {
            am.registerAudioDeviceCallback(d, main)
            registered = true
        } catch (e: Exception) { }
    }

    /**
     * Con [AUTO] non fa niente: l'audio segue il routing di sistema, esattamente
     * come fanno i lettori del Play Store che sull'autoradio si sentono.
     */
    fun apply() {
        if (Build.VERSION.SDK_INT < 28) return
        val p = player ?: return
        if (manualSink == AUTO) return
        val sink = outputs().firstOrNull { it.id == manualSink } ?: return
        try {
            p.setPreferredDevice(sink)
        } catch (e: Exception) { }
    }

    /** Sceglie l'uscita: [AUTO] oppure l'id di un dispositivo. */
    fun setSink(id: Int) {
        manualSink = if (id <= 0) AUTO else id
    }

    fun release() {
        if (registered) {
            try { devices?.let { am.unregisterAudioDeviceCallback(it) } } catch (e: Exception) { }
        }
        registered = false
        player = null
    }

    /** Uscite disponibili: solo i sink, cioè i dispositivi su cui si può mandare l'audio. */
    fun outputs(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT < 23) return emptyList()
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.filter { it.isSink } ?: emptyList()
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
        apply()
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
