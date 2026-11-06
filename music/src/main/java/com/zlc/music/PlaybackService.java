package com.zlc.music;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays the music. Runs as a media service, so playback continues with the screen off or the app
 * closed, and the notification, lock screen and headset buttons can control it.
 */
public class PlaybackService extends MediaSessionService {
    static PlaybackService inst;
    private MediaSession session;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long sleepAt = 0;
    private final Runnable sleeper = () -> {
        sleepAt = 0;
        if (session != null) session.getPlayer().pause();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        ExoPlayer player = new ExoPlayer.Builder(this)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(), true) // pauses for calls and other apps' audio
                .setHandleAudioBecomingNoisy(true) // pauses when headphones are unplugged
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                // a file that cannot be played (damaged or an unsupported format): move on to the next song
                Player p = session == null ? null : session.getPlayer();
                if (p == null) return;
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem();
                    p.prepare();
                    p.play();
                }
            }
        });
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession.Builder(this, player)
                .setSessionActivity(pi)
                .setCallback(new MediaSession.Callback() {
                    // items arrive from the app's screen without their file address; the media id is that address
                    @Override
                    public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession s, MediaSession.ControllerInfo c, List<MediaItem> items) {
                        List<MediaItem> out = new ArrayList<>();
                        for (MediaItem m : items) out.add(m.buildUpon().setUri(Uri.parse(m.mediaId)).build());
                        return Futures.immediateFuture(out);
                    }
                })
                .build();
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    /** Pause after ms milliseconds (0 cancels). */
    static void setSleep(long ms) {
        PlaybackService s = inst;
        if (s == null) return;
        s.h.removeCallbacks(s.sleeper);
        s.sleepAt = ms > 0 ? SystemClock.elapsedRealtime() + ms : 0;
        if (ms > 0) s.h.postDelayed(s.sleeper, ms);
    }

    static long sleepLeft() {
        PlaybackService s = inst;
        if (s == null || s.sleepAt == 0) return 0;
        return Math.max(0, s.sleepAt - SystemClock.elapsedRealtime());
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Player p = session == null ? null : session.getPlayer();
        if (p == null || !p.getPlayWhenReady() || p.getMediaItemCount() == 0) stopSelf();
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(sleeper);
        if (session != null) {
            session.getPlayer().release();
            session.release();
            session = null;
        }
        if (inst == this) inst = null;
        super.onDestroy();
    }
}
