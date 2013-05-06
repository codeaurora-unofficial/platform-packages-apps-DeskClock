/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.deskclock;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import android.provider.Settings;

/**
 * Manages alarms and vibe. Runs as a service so that it can continue to play
 * if another activity overrides the AlarmAlert dialog.
 */
public class AlarmKlaxon extends Service {
    // Default of 10 minutes until alarm is silenced.
    private static final String DEFAULT_ALARM_TIMEOUT = "10";

    private static final long[] sVibratePattern1 = new long[] { 500,300,500,350,500,400,500,450};
    private static final long[] sVibratePattern = new long[] { 500,500};

    private boolean mPlaying = false;
    private Vibrator mVibrator;
    private MediaPlayer mMediaPlayer;
    private Alarm mCurrentAlarm;
    private long mStartTime;
    private TelephonyManager mTelephonyManager;
    private int mInitialCallState;

    private boolean mPoweroffAlarm = false;
	private int mVolme = 0;
    private int Count = 0;
    private int mCurrentVolme = 0;
    // Internal messages
    private static final int KILLER = 1000;
    private static final int VOLUM_DELAY = 1001;
    private static final int VIBRATE_DELAY = 1002;
    AudioManager mAudioManager;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case KILLER:
                    if (Log.LOGV) {
                        Log.v("*********** Alarm killer triggered ***********");
                    }
                    //sendKillBroadcast((Alarm) msg.obj, false);
                    sendSnoozeBroadcast();
                    stopSelf();
                    // If powe on due to alarm, and no user operation, implement to power off after 2 minutes.
                    if (mPoweroffAlarm) {
                        if (Log.LOGV) Log.v("AlarmKlaxon.handler____Power off due to alarm timeout.");
                        Alarms.poweroffImme(AlarmKlaxon.this);
                    }
                    break;
                case VOLUM_DELAY:
                    
                    if(mMediaPlayer!=null){
                        if(Count < mVolme){
                        mCurrentVolme = mCurrentVolme + 1;
                        mAudioManager.setStreamVolume(AudioManager.STREAM_ALARM,mCurrentVolme,0);
                        Count++;
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(VOLUM_DELAY,mVolme),
                          2000);
                        }else{
                          mAudioManager.setStreamVolume(AudioManager.STREAM_ALARM,mVolme,0);
						  break;
                        }
                    }else{
                        mAudioManager.setStreamVolume(AudioManager.STREAM_ALARM,mVolme,0);
					    break;
                    }
                    break;
               case VIBRATE_DELAY:
                    mVibrator.cancel();
                    mVibrator.vibrate(sVibratePattern, 0);
                     
                    
            }
        }
    };

    private PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String ignored) {
            // The user might already be in a call when the alarm fires. When
            // we register onCallStateChanged, we get the initial in-call state
            // which kills the alarm. Check against the initial call state so
            // we don't kill the alarm during a call.
            if (state != TelephonyManager.CALL_STATE_IDLE
                    && state != mInitialCallState) {
                sendKillBroadcast(mCurrentAlarm, false);
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
         mAudioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        // Listen for incoming calls to kill the alarm.
        mTelephonyManager =
                (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        mTelephonyManager.listen(
                mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
        AlarmAlertWakeLock.acquireCpuWakeLock(this);
    }

    @Override
    public void onDestroy() {
        stop();
        Intent alarmDone = new Intent(Alarms.ALARM_DONE_ACTION);
        sendBroadcast(alarmDone);

        // Stop listening for incoming calls.
        mTelephonyManager.listen(mPhoneStateListener, 0);
        AlarmAlertWakeLock.releaseCpuLock();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // No intent, tell the system not to restart us.
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        final Alarm alarm = intent.getParcelableExtra(
                Alarms.ALARM_INTENT_EXTRA);

        if (!mPoweroffAlarm) {
            mPoweroffAlarm = intent.getBooleanExtra(Alarms.POWER_OFF_ALARM_FLAG, false);
        }

        if (alarm == null) {
            Log.v("AlarmKlaxon failed to parse the alarm from the intent");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (mCurrentAlarm != null) {
            sendKillBroadcast(mCurrentAlarm, true);
        }

        play(alarm);
        mCurrentAlarm = alarm;
        // Record the initial call state here so that the new alarm has the
        // newest state.
        mInitialCallState = mTelephonyManager.getCallState();

        return START_STICKY;
    }

    private void sendKillBroadcast(Alarm alarm, boolean replaced) {
        long millis = System.currentTimeMillis() - mStartTime;
        int minutes = (int) Math.round(millis / (double)DateUtils.MINUTE_IN_MILLIS);
        Intent alarmKilled = new Intent(Alarms.ALARM_KILLED);
        alarmKilled.putExtra(Alarms.ALARM_INTENT_EXTRA, alarm);
        alarmKilled.putExtra(Alarms.ALARM_KILLED_TIMEOUT, minutes);
        alarmKilled.putExtra(Alarms.ALARM_REPLACED, replaced);
        sendBroadcast(alarmKilled);
    }
    private void sendSnoozeBroadcast() {
        Intent alarmSnooze = new Intent(Alarms.ALARM_SNOOZE_ACTION);
        sendBroadcast(alarmSnooze);
    }

    // Volume suggested by media team for in-call alarms.
    private static final float IN_CALL_VOLUME = 0.125f;

    private void play(Alarm alarm) {
        // stop() checks to see if we are already playing.
        stop();

        if (Log.LOGV) {
            Log.v("AlarmKlaxon.play() " + alarm.id + " alert " + alarm.alert);
        }

        if (!alarm.silent) {
            Uri alert = alarm.alert;
            // Fall back on the default alarm if the database does not have an
            // alarm stored.
            if (alert == null) {
                alert = RingtoneManager.getDefaultUri(
                        RingtoneManager.TYPE_ALARM);
                if (Log.LOGV) {
                    Log.v("Using default alarm: " + alert.toString());
                }
            }

            // TODO: Reuse mMediaPlayer instead of creating a new one and/or use
            // RingtoneManager.
            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setOnErrorListener(new OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e("Error occurred while playing audio.");
                    mp.stop();
                    mp.release();
                    mMediaPlayer = null;
                    return true;
                }
            });

            try {
                // Check if we are in a call. If we are, use the in-call alarm
                // resource at a low volume to not disrupt the call.
                if (mTelephonyManager.getCallState()
                        != TelephonyManager.CALL_STATE_IDLE) {
                    Log.v("Using the in-call alarm");
                    mMediaPlayer.setVolume(IN_CALL_VOLUME, IN_CALL_VOLUME);
                    setDataSourceFromResource(getResources(), mMediaPlayer,
                            R.raw.in_call_alarm);
                } else {
                    mMediaPlayer.setDataSource(this, alert);
                }
                startAlarm(mMediaPlayer);
            } catch (Exception ex) {
                Log.v("Using the fallback ringtone");
                // The alert may be on the sd card which could be busy right
                // now. Use the fallback ringtone.
                try {
                    // Must reset the media player to clear the error state.
                	alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                    mMediaPlayer.reset();
					mMediaPlayer.setDataSource(this, alert);
                    //setDataSourceFromResource(getResources(), mMediaPlayer,
                    //        R.raw.fallbackring);
                    startAlarm(mMediaPlayer);
                } catch (Exception ex2) {
                    // At this point we just don't play anything.
                    Log.e("Failed to play fallback ringtone", ex2);
                }
            }
        }

        /* Start the vibrator after everything is ok with the media player */
        if (alarm.vibrate) {
            mVibrator.vibrate(sVibratePattern1, 1);
            mHandler.sendMessageDelayed(mHandler.obtainMessage(VIBRATE_DELAY),
                          3450);
            
        } else {
            mVibrator.cancel();
        }

        enableKiller(alarm);
        mPlaying = true;
        mStartTime = System.currentTimeMillis();
    }

    // Do the common stuff when starting the alarm.
    private void startAlarm(MediaPlayer player)
            throws java.io.IOException, IllegalArgumentException,
                   IllegalStateException {
        final AudioManager audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        // do not play alarms if stream volume is 0
        // (typically because ringer mode is silent).
        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) != 0) {
            startVolume(audioManager.getStreamVolume(AudioManager.STREAM_ALARM));
            player.setAudioStreamType(AudioManager.STREAM_ALARM);
            player.setLooping(true);
            player.prepare();
            player.start();
            
        }
    }
    private void startVolume(int i){
        mVolme = i; 
        mHandler.sendMessageDelayed(mHandler.obtainMessage(VOLUM_DELAY, i),
                          100);
    }
    private void setDataSourceFromResource(Resources resources,
            MediaPlayer player, int res) throws java.io.IOException {
        AssetFileDescriptor afd = resources.openRawResourceFd(res);
        if (afd != null) {
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(),
                    afd.getLength());
            afd.close();
        }
    }

    /**
     * Stops alarm audio and disables alarm if it not snoozed and not
     * repeating
     */
    public void stop() {
        if (Log.LOGV) Log.v("AlarmKlaxon.stop()");
        if(mHandler.hasMessages(VIBRATE_DELAY)){
            mHandler.removeMessages(VIBRATE_DELAY);
		}
		if(mHandler.hasMessages(VOLUM_DELAY)){
            mHandler.removeMessages(VOLUM_DELAY);
		}
        if (mPlaying) {
            mPlaying = false;

            // Stop audio playing
            if (mMediaPlayer != null) {
                mAudioManager.setStreamVolume(AudioManager.STREAM_ALARM,mVolme,0);
                mMediaPlayer.stop();
                mMediaPlayer.release();
                mMediaPlayer = null;
            }
            // Stop vibrator
            mVibrator.cancel();
        }
        disableKiller();
    }

    /**
     * Kills alarm audio after ALARM_TIMEOUT_SECONDS, so the alarm
     * won't run all day.
     *
     * This just cancels the audio, but leaves the notification
     * popped, so the user will know that the alarm tripped.
     */
    private void enableKiller(Alarm alarm) {
        final String autoSnooze =
                PreferenceManager.getDefaultSharedPreferences(this)
                .getString(SettingsActivity.KEY_AUTO_SILENCE,
                        DEFAULT_ALARM_TIMEOUT);
        int autoSnoozeMinutes = Integer.parseInt(autoSnooze);
        if (autoSnoozeMinutes != -1) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(KILLER, alarm),
                    autoSnoozeMinutes * DateUtils.MINUTE_IN_MILLIS);
        }
    }

    private void disableKiller() {
        mHandler.removeMessages(KILLER);
    }


}
