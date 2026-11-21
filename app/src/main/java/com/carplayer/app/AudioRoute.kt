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
 * Instradamento dell'audio pensato per le autoradio cinesi (RK3326, PX30, MTK...) con
 * Android 13-15: lì il media finisce sulla rotta di telefonia invece che sulle casse.
 * In tendina compare "Altoparlante telefono", il Bluetooth resta acceso anche senza
 * dispositivi collegati, lo stato dice IN RIPRODUZIONE ma dall'amplificatore non esce nulla.
 *
 * Tre cause, tutte coperte qui:
 *  1. AudioManager lasciato in MODE_IN_CALL / MODE_IN_COMMUNICATION dall'app telefono:
 *     la traccia viene mandata alla capsula e le casse restano mute -> si riporta MODE_NORMAL;
 *  2. SCO Bluetooth rimasto aperto: la radio crede di essere in chiamata, prende il focus e
 *     mette in pausa la radio FM, ma non riproduce niente -> si chiude il dispositivo di
 *     comunicazione;
 *  3. audio policy che tiene la rotta A2DP/telefonica anche se nessun dispositivo è
 *     collegato -> si sceglie esplicitamente l'uscita interna con setPreferredDevice (API 28+).
 *
 * Quando invece è collegata un'uscita esterna vera (cuffie, USB, Bluetooth A2DP) il
 * dispositivo preferito viene rimosso e si torna al routing normale di Android.
 */
class AudioRoute(context: Context) {

    private val am = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var registered = false

    /** true quando la musica è forzata sulle casse interne (nessuna uscita esterna collegata). */
    @Volatile
    var onBuiltInSpeaker = false
        private set

    /**
     * Uscita scelta a mano: [AUTO] = decide l'app, altrimenti l'id di un AudioDeviceInfo.
     * Serve sulle autoradio che instradano male: toccando lo stato in alto si cambia.
     */
    var manualSink = AUTO

    private val devices: AudioDeviceCallback? =
        if (Build.VERSION.SDK_INT >= 23) object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                main.post { routeToSink() }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                main.post { routeToSink() }
            }
        } else null

    /** Aggancia il player (subito dopo reset) e sceglie subito l'uscita. */
    fun attach(p: MediaPlayer) {
        player = p
        if (!registered) {
            try {
                devices?.let { am.registerAudioDeviceCallback(it, main) }
                registered = true
            } catch (e: Exception) { }
        }
        routeToSink()
    }

    /**
     * Instradamento completo: si usa solo quando la musica sta per partire (focus già
     * ottenuto), così non si tocca la modalità audio di una chiamata in corso.
     */
    fun apply() {
        clearPhoneRoute()
        routeToSink()
    }

    fun release() {
        if (registered) {
            try { devices?.let { am.unregisterAudioDeviceCallback(it) } } catch (e: Exception) { }
        }
        registered = false
        player = null
    }

    // ---------------------------------------------------------------- interno

    /**
     * Manda la traccia sulle casse interne (o torna al routing di sistema se serve).
     * Se l'HAL non accetta il dispositivo preferito si prova anche la via "storica"
     * delle autoradio RK: speakerphone acceso con modalità normale.
     */
    @Suppress("DEPRECATION")
    private fun routeToSink() {
        val p = player ?: return
        val sink = preferredSink()
        val accepted = try {
            if (Build.VERSION.SDK_INT >= 28 && sink != null) p.setPreferredDevice(sink) else true
        } catch (e: Exception) {
            false
        }
        onBuiltInSpeaker = sink != null && accepted && !isExternal(sink.type)
        try {
            if (sink != null && !accepted) am.isSpeakerphoneOn = true
            else if (sink == null) am.isSpeakerphoneOn = false
        } catch (e: Exception) { }
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
        routeToSink()
        if (manualSink == AUTO) return "Automatico"
        return outs.firstOrNull { it.id == manualSink }?.let { label(it) } ?: "Automatico"
    }

    private fun label(d: AudioDeviceInfo): String {
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

    /**
     * Toglie la radio dalla "modalità telefono": modo normale e nessun SCO aperto.
     * Se un'app di telefonia è davvero in chiamata è lei a rimettere il modo giusto,
     * e comunque questo passa solo quando la musica ha già ottenuto il focus.
     */
    @Suppress("DEPRECATION")
    private fun clearPhoneRoute() {
        try {
            val m = am.mode
            if (m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION) {
                am.mode = AudioManager.MODE_NORMAL
            }
        } catch (e: Exception) { }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.communicationDevice != null) am.clearCommunicationDevice()
            } else if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
        } catch (e: Exception) { }
    }

    /**
     * Uscita da preferire: le casse interne, a meno che non ci sia davvero un'uscita
     * esterna collegata (in quel caso null = routing normale di Android).
     */
    private fun preferredSink(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 23) return null
        val outs = outputs()
        if (manualSink > 0) {
            val chosen = outs.firstOrNull { it.id == manualSink }
            if (chosen != null) return chosen
            manualSink = AUTO // non più disponibile: torna automatico
        }
        if (outs.any { isExternal(it.type) }) return null
        return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: outs.firstOrNull { !isPhoneRoute(it.type) }
    }

    private fun isPhoneRoute(t: Int) = t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        t == AudioDeviceInfo.TYPE_TELEPHONY

    private fun isExternal(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_DOCK_ANALOG -> true
        else -> false
    }

    companion object {
        /** Nessuna scelta a mano: l'app preferisce le casse interne. */
        const val AUTO = 0
    }
}
