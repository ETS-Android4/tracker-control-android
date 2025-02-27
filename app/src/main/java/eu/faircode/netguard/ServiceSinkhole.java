/*
 * This file is from NetGuard.
 *
 * NetGuard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * NetGuard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2015–2020 by Marcel Bokhorst (M66B), Konrad
 * Kollnig (University of Oxford)
 */

package eu.faircode.netguard;

import static android.app.Activity.RESULT_OK;
import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_NAME;
import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME;
import static net.kollnig.missioncontrol.DetailsActivity.INTENT_EXTRA_APP_UID;
import static net.kollnig.missioncontrol.data.TrackerBlocklist.NECESSARY_CATEGORY;
import static eu.faircode.netguard.WidgetAdmin.INTENT_PAUSE;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.TypedValue;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.BuildConfig;
import net.kollnig.missioncontrol.Common;
import net.kollnig.missioncontrol.DetailsActivity;
import net.kollnig.missioncontrol.R;
import net.kollnig.missioncontrol.analysis.AnalysisException;
import net.kollnig.missioncontrol.analysis.TrackerLibraryAnalyser;
import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerList;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;

public class ServiceSinkhole extends VpnService {
    private static final String TAG = "TrackerControl.VPN";

    private boolean registeredUser = false;
    private boolean registeredIdleState = false;
    private boolean registeredConnectivityChanged = false;
    private boolean registeredPackageChanged = false;

    private boolean phone_state = false;
    private Object networkCallback = null;

    private boolean registeredInteractiveState = false;
    private PhoneStateListener callStateListener = null;

    private State state = State.none;
    private boolean user_foreground = true;
    private boolean last_connected = false;
    private boolean last_metered = true;
    private boolean last_interactive = false;

    private int last_allowed = -1;
    private int last_blocked = -1;
    private int last_hosts = -1;

    private static Object jni_lock = new Object();
    private static long jni_context = 0;
    private Thread tunnelThread = null;
    private ServiceSinkhole.Builder last_builder = null;
    private ParcelFileDescriptor vpn = null;
    private boolean temporarilyStopped = false;

    private static long last_hosts_modified = 0;
    public static Map<String, Boolean> mapHostsBlocked = new ConcurrentHashMap<>();
    private Map<Integer, Boolean> mapUidAllowed = new HashMap<>();
    private Map<Integer, Integer> mapUidKnown = new HashMap<>();
    private final Map<IPKey, Map<InetAddress, IPRule>> mapUidIPFilters = new HashMap<>();
    private Map<Integer, Forward> mapForward = new HashMap<>();
    public static ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    private volatile Looper commandLooper;
    private volatile Looper logLooper;
    private volatile Looper statsLooper;
    private volatile CommandHandler commandHandler;
    private volatile LogHandler logHandler;
    private volatile StatsHandler statsHandler;

    private static final int NOTIFY_ENFORCING = 1;
    private static final int NOTIFY_WAITING = 2;
    private static final int NOTIFY_DISABLED = 3;
    private static final int NOTIFY_LOCKDOWN = 4;
    private static final int NOTIFY_AUTOSTART = 5;
    private static final int NOTIFY_ERROR = 6;
    private static final int NOTIFY_TRAFFIC = 7;
    private static final int NOTIFY_UPDATE = 8;
    public static final int NOTIFY_EXTERNAL = 9;
    public static final int NOTIFY_DOWNLOAD = 10;

    public static final String EXTRA_COMMAND = "Command";
    private static final String EXTRA_REASON = "Reason";
    public static final String EXTRA_NETWORK = "Network";
    public static final String EXTRA_UID = "UID";
    public static final String EXTRA_PACKAGE = "Package";
    public static final String EXTRA_BLOCKED = "Blocked";
    public static final String EXTRA_INTERACTIVE = "Interactive";
    public static final String EXTRA_TEMPORARY = "Temporary";

    private static final int MSG_STATS_START = 1;
    private static final int MSG_STATS_STOP = 2;
    private static final int MSG_STATS_UPDATE = 3;
    private static final int MSG_PACKET = 4;
    private static final int MSG_USAGE = 5;

    private enum State {none, waiting, enforcing, stats}

    public enum Command {run, start, reload, stop, stats, set, householding, watchdog}

    private static volatile PowerManager.WakeLock wlInstance = null;

    private ExecutorService executor = Executors.newCachedThreadPool();

    private static final String ACTION_HOUSE_HOLDING = "eu.faircode.netguard.HOUSE_HOLDING";
    private static final String ACTION_SCREEN_OFF_DELAYED = "eu.faircode.netguard.SCREEN_OFF_DELAYED";
    private static final String ACTION_WATCHDOG = "eu.faircode.netguard.WATCHDOG";

    private native long jni_init(int sdk);

    private native void jni_start(long context, int loglevel);

    private native void jni_run(long context, int tun, boolean fwd53, int rcode);

    private native void jni_stop(long context);

    private native void jni_clear(long context);

    private native int jni_get_mtu();

    private native int[] jni_get_stats(long context);

    private static native void jni_pcap(String name, int record_size, int file_size);

    private native void jni_socks5(String addr, int port, String username, String password);

    private native void jni_done(long context);

    public static void setPcap(boolean enabled, Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        int record_size = 64;
        try {
            String r = prefs.getString("pcap_record_size", null);
            if (TextUtils.isEmpty(r))
                r = "64";
            record_size = Integer.parseInt(r);
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }

        int file_size = 2 * 1024 * 1024;
        try {
            String f = prefs.getString("pcap_file_size", null);
            if (TextUtils.isEmpty(f))
                f = "2";
            file_size = Integer.parseInt(f) * 1024 * 1024;
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }

        File pcap = (enabled ? new File(context.getDir("data", MODE_PRIVATE), "netguard.pcap") : null);
        jni_pcap(pcap == null ? null : pcap.getAbsolutePath(), record_size, file_size);
    }

    synchronized private static PowerManager.WakeLock getLock(Context context) {
        if (wlInstance == null) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            wlInstance = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, context.getString(R.string.app_name) + " wakelock");
            wlInstance.setReferenceCounted(true);
        }
        return wlInstance;
    }

    synchronized private static void releaseLock(Context context) {
        if (wlInstance != null) {
            while (wlInstance.isHeld())
                wlInstance.release();
            wlInstance = null;
        }
    }

    private final class CommandHandler extends Handler {
        public int queue = 0;

        public CommandHandler(Looper looper) {
            super(looper);
        }

        private void reportQueueSize() {
            Intent ruleset = new Intent(ActivityMain.ACTION_QUEUE_CHANGED);
            ruleset.putExtra(ActivityMain.EXTRA_SIZE, queue);
            LocalBroadcastManager.getInstance(ServiceSinkhole.this).sendBroadcast(ruleset);
        }

        public void queue(Intent intent) {
            synchronized (this) {
                queue++;
                reportQueueSize();
            }
            Command cmd = (Command) intent.getSerializableExtra(EXTRA_COMMAND);
            Message msg = commandHandler.obtainMessage();
            msg.obj = intent;
            msg.what = cmd.ordinal();
            commandHandler.sendMessage(msg);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                synchronized (ServiceSinkhole.this) {
                    handleIntent((Intent) msg.obj);
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            } finally {
                synchronized (this) {
                    queue--;
                    reportQueueSize();
                }
                try {
                    PowerManager.WakeLock wl = getLock(ServiceSinkhole.this);
                    if (wl.isHeld())
                        wl.release();
                    else
                        Log.w(TAG, "Wakelock under-locked");
                    Log.i(TAG, "Messages=" + hasMessages(0) + " wakelock=" + wlInstance.isHeld());
                } catch (Throwable ex) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                }
            }
        }

        private void handleIntent(Intent intent) {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);

            Command cmd = (Command) intent.getSerializableExtra(EXTRA_COMMAND);
            String reason = intent.getStringExtra(EXTRA_REASON);
            Log.i(TAG, "Executing intent=" + intent + " command=" + cmd + " reason=" + reason +
                    " vpn=" + (vpn != null) + " user=" + (Process.myUid() / 100000));

            // Check if foreground
            if (cmd != Command.stop)
                if (!user_foreground) {
                    Log.i(TAG, "Command " + cmd + " ignored for background user");
                    return;
                }

            // Handle temporary stop
            if (cmd == Command.stop)
                temporarilyStopped = intent.getBooleanExtra(EXTRA_TEMPORARY, false);
            else if (cmd == Command.start)
                temporarilyStopped = false;
            else if (cmd == Command.reload && temporarilyStopped) {
                // Prevent network/interactive changes from restarting the VPN
                Log.i(TAG, "Command " + cmd + " ignored because of temporary stop");
                return;
            }

            // Optionally listen for interactive state changes
            if (prefs.getBoolean("screen_on", true)) {
                if (!registeredInteractiveState) {
                    Log.i(TAG, "Starting listening for interactive state changes");
                    last_interactive = Util.isInteractive(ServiceSinkhole.this);
                    IntentFilter ifInteractive = new IntentFilter();
                    ifInteractive.addAction(Intent.ACTION_SCREEN_ON);
                    ifInteractive.addAction(Intent.ACTION_SCREEN_OFF);
                    ifInteractive.addAction(ACTION_SCREEN_OFF_DELAYED);
                    registerReceiver(interactiveStateReceiver, ifInteractive);
                    registeredInteractiveState = true;
                }
            } else {
                if (registeredInteractiveState) {
                    Log.i(TAG, "Stopping listening for interactive state changes");
                    unregisterReceiver(interactiveStateReceiver);
                    registeredInteractiveState = false;
                    last_interactive = false;
                }
            }

            // Optionally listen for call state changes
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (prefs.getBoolean("disable_on_call", false)) {
                if (tm != null && callStateListener == null && Util.hasPhoneStatePermission(ServiceSinkhole.this)) {
                    Log.i(TAG, "Starting listening for call states");
                    PhoneStateListener listener = new PhoneStateListener() {
                        @Override
                        public void onCallStateChanged(int state, String incomingNumber) {
                            Log.i(TAG, "New call state=" + state);
                            if (prefs.getBoolean("enabled", false))
                                if (state == TelephonyManager.CALL_STATE_IDLE)
                                    ServiceSinkhole.start("call state", ServiceSinkhole.this);
                                else
                                    ServiceSinkhole.stop("call state", ServiceSinkhole.this, true);
                        }
                    };
                    tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
                    callStateListener = listener;
                }
            } else {
                if (tm != null && callStateListener != null) {
                    Log.i(TAG, "Stopping listening for call states");
                    tm.listen(callStateListener, PhoneStateListener.LISTEN_NONE);
                    callStateListener = null;
                }
            }

            // Watchdog
            if (cmd == Command.start || cmd == Command.reload || cmd == Command.stop) {
                Intent watchdogIntent = new Intent(ServiceSinkhole.this, ServiceSinkhole.class);
                watchdogIntent.setAction(ACTION_WATCHDOG);
                PendingIntent pi;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    pi = PendingIntent.getForegroundService(ServiceSinkhole.this, 1, watchdogIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                else
                    pi = PendingIntent.getService(ServiceSinkhole.this, 1, watchdogIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

                AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                am.cancel(pi);

                if (cmd != Command.stop) {
                    int watchdog = Integer.parseInt(prefs.getString("watchdog", "0"));
                    if (watchdog > 0) {
                        Log.i(TAG, "Watchdog " + watchdog + " minutes");
                        am.setInexactRepeating(AlarmManager.RTC, SystemClock.elapsedRealtime() + watchdog * 60 * 1000, watchdog * 60 * 1000, pi);
                    }
                }
            }

            try {
                switch (cmd) {
                    case run:
                        break;

                    case start:
                        start();
                        break;

                    case reload:
                        reload(intent.getBooleanExtra(EXTRA_INTERACTIVE, false));
                        break;

                    case stop:
                        stop(temporarilyStopped);
                        break;

                    case stats:
                        statsHandler.sendEmptyMessage(MSG_STATS_STOP);
                        statsHandler.sendEmptyMessage(MSG_STATS_START);
                        break;

                    case householding:
                        householding(intent);
                        break;

                    case watchdog:
                        watchdog(intent);
                        break;

                    default:
                        Log.e(TAG, "Unknown command=" + cmd);
                }

                if (cmd == Command.start || cmd == Command.reload) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        boolean filter = prefs.getBoolean("filter", false);
                        if (filter && isLockdownEnabled())
                            showLockdownNotification();
                        else
                            removeLockdownNotification();
                    }
                }

                if (cmd == Command.start || cmd == Command.reload || cmd == Command.stop) {
                    // Update main view
                    Intent ruleset = new Intent(ActivityMain.ACTION_RULES_CHANGED);
                    ruleset.putExtra(ActivityMain.EXTRA_CONNECTED, cmd == Command.stop ? false : last_connected);
                    ruleset.putExtra(ActivityMain.EXTRA_METERED, cmd == Command.stop ? false : last_metered);
                    LocalBroadcastManager.getInstance(ServiceSinkhole.this).sendBroadcast(ruleset);

                    // Update widgets
                    WidgetMain.updateWidgets(ServiceSinkhole.this);
                }

                // Stop service if needed
                if (!commandHandler.hasMessages(Command.start.ordinal()) &&
                        !commandHandler.hasMessages(Command.reload.ordinal()) &&
                        !prefs.getBoolean("enabled", false) &&
                        !prefs.getBoolean("show_stats", false))
                    stopForeground(true);

                // Request garbage collection
                System.gc();
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));

                if (cmd == Command.start || cmd == Command.reload) {
                    if (VpnService.prepare(ServiceSinkhole.this) == null) {
                        Log.w(TAG, "VPN prepared connected=" + last_connected);
                        if (last_connected && !(ex instanceof StartFailedException)) {
                            //showAutoStartNotification();
                            if (!Util.isPlayStoreInstall(ServiceSinkhole.this))
                                showErrorNotification(ex.toString());
                        }
                        // Retried on connectivity change
                    } else {
                        showErrorNotification(ex.toString());

                        // Disable firewall
                        if (!(ex instanceof StartFailedException)) {
                            prefs.edit().putBoolean("enabled", false).apply();
                            WidgetMain.updateWidgets(ServiceSinkhole.this);
                        }
                    }
                } else
                    showErrorNotification(ex.toString());
            }
        }

        private void start() {
            if (vpn == null) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=" + state.toString());
                    stopForeground(true);
                }
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1));
                state = State.enforcing;
                Log.d(TAG, "Start foreground state=" + state.toString());

                List<Rule> listRule = Rule.getRules(true, ServiceSinkhole.this);
                List<Rule> listAllowed = getAllowedRules(listRule);

                last_builder = getBuilder(listAllowed, listRule);
                vpn = startVPN(last_builder);
                if (vpn == null)
                    throw new StartFailedException(getString((R.string.msg_start_failed)));

                startNative(vpn, listAllowed, listRule);

                removeWarningNotifications();
                updateEnforcingNotification(listAllowed.size(), listRule.size());
            }
        }

        private void reload(boolean interactive) {
            List<Rule> listRule = Rule.getRules(true, ServiceSinkhole.this);

            // Check if rules needs to be reloaded
            if (interactive) {
                boolean process = false;
                for (Rule rule : listRule) {
                    boolean blocked = (last_metered ? rule.other_blocked : rule.wifi_blocked);
                    boolean screen = (last_metered ? rule.screen_other : rule.screen_wifi);
                    if (blocked && screen) {
                        process = true;
                        break;
                    }
                }
                if (!process) {
                    Log.i(TAG, "No changed rules on interactive state change");
                    return;
                }
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);

            if (state != State.enforcing) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=" + state.toString());
                    stopForeground(true);
                }
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1));
                state = State.enforcing;
                Log.d(TAG, "Start foreground state=" + state.toString());
            }

            List<Rule> listAllowed = getAllowedRules(listRule);
            ServiceSinkhole.Builder builder = getBuilder(listAllowed, listRule);

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                last_builder = builder;
                Log.i(TAG, "Legacy restart");

                if (vpn != null) {
                    stopNative(vpn);
                    stopVPN(vpn);
                    vpn = null;
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                }
                vpn = startVPN(last_builder);

            } else {
                if (vpn != null && prefs.getBoolean("filter", true) && builder.equals(last_builder)) {
                    Log.i(TAG, "Native restart");
                    stopNative(vpn);

                } else {
                    last_builder = builder;

                    boolean handover = prefs.getBoolean("handover", false);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        handover = false;
                    Log.i(TAG, "VPN restart handover=" + handover);

                    if (handover) {
                        // Attempt seamless handover
                        ParcelFileDescriptor prev = vpn;
                        vpn = startVPN(builder);

                        if (prev != null && vpn == null) {
                            Log.w(TAG, "Handover failed");
                            stopNative(prev);
                            stopVPN(prev);
                            prev = null;
                            try {
                                Thread.sleep(3000);
                            } catch (InterruptedException ignored) {
                            }
                            vpn = startVPN(last_builder);
                            if (vpn == null)
                                throw new IllegalStateException("Handover failed");
                        }

                        if (prev != null) {
                            stopNative(prev);
                            stopVPN(prev);
                        }
                    } else {
                        if (vpn != null) {
                            stopNative(vpn);
                            stopVPN(vpn);
                        }

                        vpn = startVPN(builder);
                    }
                }
            }

            if (vpn == null)
                throw new StartFailedException(getString((R.string.msg_start_failed)));

            startNative(vpn, listAllowed, listRule);

            removeWarningNotifications();
            updateEnforcingNotification(listAllowed.size(), listRule.size());
        }

        private void stop(boolean temporary) {
            if (vpn != null) {
                stopNative(vpn);
                stopVPN(vpn);
                vpn = null;
                unprepare();
            }
            if (state == State.enforcing && !temporary) {
                Log.d(TAG, "Stop foreground state=" + state.toString());
                last_allowed = -1;
                last_blocked = -1;
                last_hosts = -1;

                stopForeground(true);

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                if (prefs.getBoolean("show_stats", false)) {
                    startForeground(NOTIFY_WAITING, getWaitingNotification());
                    state = State.waiting;
                    Log.d(TAG, "Start foreground state=" + state.toString());
                } else {
                    state = State.none;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        stopForeground(true);
                    } else {
                        stopSelf();
                    }
                }
            }
        }

        // Called every 12 hours
        private void householding(Intent intent) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);

            // Keep log records for three days, only wipe if logging on
            if (prefs.getBoolean("log", false))
                DatabaseHelper.getInstance(ServiceSinkhole.this).cleanupLog(new Date().getTime() - 3 * 24 * 3600 * 1000L);

            // Clear expired DNS records
            DatabaseHelper.getInstance(ServiceSinkhole.this).cleanupDns();

            // Refresh mappings regularly
            ipToHost.clear();
            ipToTracker.clear();
            uidToApp.clear();

            // Check for update
            if (!Util.isPlayStoreInstall(ServiceSinkhole.this) && !Util.isFDroidInstall() && prefs.getBoolean("update_check", true))
                checkUpdate();
        }

        private void watchdog(Intent intent) {
            if (vpn == null) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                if (prefs.getBoolean("enabled", false)) {
                    Log.e(TAG, "Service was killed");
                    start();
                }
            }
        }

        private void checkUpdate() {
            StringBuilder json = new StringBuilder();
            HttpsURLConnection urlConnection = null;
            try {
                URL url = new URL(BuildConfig.GITHUB_LATEST_API);
                urlConnection = (HttpsURLConnection) url.openConnection();
                urlConnection.setRequestProperty("Accept-Encoding", "gzip");
                BufferedReader br;
                if ("gzip".equals(urlConnection.getContentEncoding()))
                    br = new BufferedReader(new InputStreamReader(new GZIPInputStream(urlConnection.getInputStream())));
                else
                    br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));

                String line;
                while ((line = br.readLine()) != null)
                    json.append(line);
                br.close();

            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            } finally {
                if (urlConnection != null)
                    urlConnection.disconnect();
            }

            try {
                JSONArray jarray = new JSONArray(json.toString());
                if (jarray.length() < 1)
                    return;
                JSONObject jroot = jarray.getJSONObject(0);
                //JSONObject jroot = new JSONObject(json.toString());
                if (jroot.has("tag_name") && jroot.has("html_url") && jroot.has("assets")) {
                    String url = jroot.getString("html_url");
                    JSONArray jassets = jroot.getJSONArray("assets");
                    if (jassets.length() > 0) {
                        JSONObject jasset = jassets.getJSONObject(0);
                        if (jasset.has("name")) {
                            long available = jroot.getLong("tag_name");
                            String name = jasset.getString("name");
                            Log.i(TAG, "Tag " + available + " name " + name + " url " + url);

                            long current = Util.getSelfVersionCode(ServiceSinkhole.this);
                            if (current < available) {
                                Log.i(TAG, "Update available from " + current + " to " + available);
                                showUpdateNotification(name, url);
                            } else
                                Log.i(TAG, "Up-to-date current version " + current);
                        }
                    }
                }
            } catch (JSONException ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
        }

        private class StartFailedException extends IllegalStateException {
            public StartFailedException(String msg) {
                super(msg);
            }
        }
    }

    private final class LogHandler extends Handler {
        public int queue = 0;

        private static final int MAX_QUEUE = 250;

        public LogHandler(Looper looper) {
            super(looper);
        }

        public void queue(Packet packet) {
            Message msg = obtainMessage();
            msg.obj = packet;
            msg.what = MSG_PACKET;
            msg.arg1 = (last_connected ? (last_metered ? 2 : 1) : 0);
            msg.arg2 = (last_interactive ? 1 : 0);

            synchronized (this) {
                if (queue > MAX_QUEUE) {
                    Log.w(TAG, "Log queue full");
                    return;
                }

                sendMessage(msg);

                queue++;
            }
        }

        public void account(Usage usage) {
            Message msg = obtainMessage();
            msg.obj = usage;
            msg.what = MSG_USAGE;

            synchronized (this) {
                if (queue > MAX_QUEUE) {
                    Log.w(TAG, "Log queue full");
                    return;
                }

                sendMessage(msg);

                queue++;
            }
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_PACKET:
                        log((Packet) msg.obj, msg.arg1, msg.arg2 > 0);
                        break;

                    case MSG_USAGE:
                        usage((Usage) msg.obj);
                        break;

                    default:
                        Log.e(TAG, "Unknown log message=" + msg.what);
                }

                synchronized (this) {
                    queue--;
                }

            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
        }

        private void log(Packet packet, int connection, boolean interactive) {
            // Get settings
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
            boolean log = prefs.getBoolean("log", false);
            boolean log_app = prefs.getBoolean("log_app", true);

            DatabaseHelper dh = DatabaseHelper.getInstance(ServiceSinkhole.this);

            String dname = null;
            String originalDname = null;

            int uncertain = 0;
            boolean isTracker = false;
            try(Cursor lookup = dh.getQAName(packet.uid, packet.daddr, false)) {
                uncertain = (lookup != null
                        && lookup.getCount() > 1) ? 1 : 0;

                // Loop until we find tracker or reach last entry
                if (lookup != null) {
                    Pair<Tracker, String> foundTracker = new Pair<>(NO_TRACKER, null);

                    while (lookup.moveToNext()) {
                        dname = lookup.getString(lookup.getColumnIndex("qname"));
                        if (dname != null)  {
                            originalDname = dname;

                            String aname = lookup.getString(lookup.getColumnIndex("aname"));
                            Pair<Tracker, String> p = getDecloakedTracker(dname, aname);

                            if (foundTracker.first == NO_TRACKER
                                    && p.first != null) { // store found tracker
                                isTracker = true;
                                foundTracker = p;
                            }

                            if (foundTracker.first != NO_TRACKER
                                    && (p.first == null // could have uncertain tracker company if no company found for an observed domain
                                    || !Objects.equals(foundTracker.first.name, p.first.name)) // we have an uncertain tracker company
                                    && uncertain == 1) {
                                uncertain = 2;
                                break;
                            }
                        }
                    }

                    if (foundTracker.first != NO_TRACKER)
                        dname = foundTracker.second;
                }
            }

            // Check if we have additional information from SNI
            if (packet.data != null
                    && !packet.data.isEmpty()) {
                uncertain = 0;

                if (!packet.data.equals(originalDname)) {
                    Log.d(TAG, "Using SNI " + packet.data + " instead of originalDname " + originalDname);
                    dname = packet.data;
                    isTracker = getDecloakedTracker(dname, dh).first != null;
                }
            }

            if (uncertain == 1) // multiple dnames correspond to same IP address
                Log.d(TAG, "Found uncertain entry: " + dname);

            // Fallback: Check for IP-based tracking
            if (dname == null
                    && TrackerList.trackingIps.contains(packet.daddr))
                isTracker = true;

            // Traffic log
            if (log)
                dh.insertLog(packet, dname, connection, interactive);

            // Application log
            if (log_app && isTracker && packet.uid >= 0 &&
                    !(packet.uid == 0 && (packet.protocol == 6 || packet.protocol == 17) && packet.dport == 53)) {
                if (!(packet.protocol == 6 /* TCP */ || packet.protocol == 17 /* UDP */))
                    packet.dport = 0;

                dh.updateAccess(packet, dname, -1, uncertain);
            }
        }

        private Pair<Tracker, String> getDecloakedTracker(String qname, DatabaseHelper dh) {
            Cursor lookup = dh.getAName(qname, false);
            String aname = null;
            if (lookup != null) {
                if (lookup.moveToNext())
                    aname = lookup.getString(lookup.getColumnIndex("aname"));
                lookup.close();
            }
            return getDecloakedTracker(qname, aname);
        }

        private Pair<Tracker, String> getDecloakedTracker(String qname, String aname) {
            String foundDname = null;
            Tracker t = TrackerList.findTracker(qname);

            if (t != null) {
                foundDname = qname;
            } else { // DNS uncloaking
                if (aname != null) {
                    t = TrackerList.findTracker(aname);
                    if (t != null)
                        foundDname = aname;
                }
            }

            return new Pair<>(t, foundDname);
        }

        private void usage(Usage usage) {
            if (usage.Uid >= 0 && !(usage.Uid == 0 && usage.Protocol == 17 && usage.DPort == 53)) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                boolean filter = prefs.getBoolean("filter", true);
                boolean log_app = prefs.getBoolean("log_app", true);
                boolean track_usage = prefs.getBoolean("track_usage", false);
                if (filter && log_app && track_usage) {
                    DatabaseHelper dh = DatabaseHelper.getInstance(ServiceSinkhole.this);
                    String dname = dh.getQName(usage.Uid, usage.DAddr);
                    Log.i(TAG, "Usage account " + usage + " dname=" + dname);
                    dh.updateUsage(usage, dname);
                }
            }
        }
    }

    private final class StatsHandler extends Handler {
        private boolean stats = false;
        private long when;

        private long t = -1;
        private long tx = -1;
        private long rx = -1;

        private List<Long> gt = new ArrayList<>();
        private List<Float> gtx = new ArrayList<>();
        private List<Float> grx = new ArrayList<>();

        private HashMap<Integer, Long> mapUidBytes = new HashMap<>();

        public StatsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_STATS_START:
                        startStats();
                        break;

                    case MSG_STATS_STOP:
                        stopStats();
                        break;

                    case MSG_STATS_UPDATE:
                        updateStats();
                        break;

                    default:
                        Log.e(TAG, "Unknown stats message=" + msg.what);
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
        }

        private void startStats() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
            boolean enabled = (!stats && prefs.getBoolean("show_stats", false));
            Log.i(TAG, "Stats start enabled=" + enabled);
            if (enabled) {
                when = new Date().getTime();
                t = -1;
                tx = -1;
                rx = -1;
                gt.clear();
                gtx.clear();
                grx.clear();
                mapUidBytes.clear();
                stats = true;
                updateStats();
            }
        }

        private void stopStats() {
            Log.i(TAG, "Stats stop");
            stats = false;
            this.removeMessages(MSG_STATS_UPDATE);
            if (state == State.stats) {
                Log.d(TAG, "Stop foreground state=" + state.toString());
                stopForeground(true);
                state = State.none;
            } else
                NotificationManagerCompat.from(ServiceSinkhole.this).cancel(NOTIFY_TRAFFIC);
        }

        private void updateStats() {
            RemoteViews remoteViews = new RemoteViews(getPackageName(), R.layout.traffic);
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
            long frequency = Long.parseLong(prefs.getString("stats_frequency", "1000"));
            long samples = Long.parseLong(prefs.getString("stats_samples", "90"));
            boolean filter = prefs.getBoolean("filter", true);
            boolean show_top = prefs.getBoolean("show_top", false);
            int loglevel = Integer.parseInt(prefs.getString("loglevel", Integer.toString(Log.WARN)));

            // Schedule next update
            this.sendEmptyMessageDelayed(MSG_STATS_UPDATE, frequency);

            long ct = SystemClock.elapsedRealtime();

            // Cleanup
            while (gt.size() > 0 && ct - gt.get(0) > samples * 1000) {
                gt.remove(0);
                gtx.remove(0);
                grx.remove(0);
            }

            // Calculate network speed
            float txsec = 0;
            float rxsec = 0;
            long ttx = TrafficStats.getTotalTxBytes();
            long trx = TrafficStats.getTotalRxBytes();
            if (filter) {
                ttx -= TrafficStats.getUidTxBytes(Process.myUid());
                trx -= TrafficStats.getUidRxBytes(Process.myUid());
                if (ttx < 0)
                    ttx = 0;
                if (trx < 0)
                    trx = 0;
            }
            if (t > 0 && tx > 0 && rx > 0) {
                float dt = (ct - t) / 1000f;
                txsec = (ttx - tx) / dt;
                rxsec = (trx - rx) / dt;
                gt.add(ct);
                gtx.add(txsec);
                grx.add(rxsec);
            }

            // Calculate application speeds
            if (show_top) {
                if (mapUidBytes.size() == 0) {
                    for (ApplicationInfo ainfo : getPackageManager().getInstalledApplications(0))
                        if (ainfo.uid != Process.myUid())
                            mapUidBytes.put(ainfo.uid, TrafficStats.getUidTxBytes(ainfo.uid) + TrafficStats.getUidRxBytes(ainfo.uid));

                } else if (t > 0) {
                    TreeMap<Float, Integer> mapSpeedUid = new TreeMap<>(new Comparator<Float>() {
                        @Override
                        public int compare(Float value, Float other) {
                            return -value.compareTo(other);
                        }
                    });
                    float dt = (ct - t) / 1000f;
                    for (int uid : mapUidBytes.keySet()) {
                        long bytes = TrafficStats.getUidTxBytes(uid) + TrafficStats.getUidRxBytes(uid);
                        float speed = (bytes - mapUidBytes.get(uid)) / dt;
                        if (speed > 0) {
                            mapSpeedUid.put(speed, uid);
                            mapUidBytes.put(uid, bytes);
                        }
                    }

                    StringBuilder sb = new StringBuilder();
                    int i = 0;
                    for (float speed : mapSpeedUid.keySet()) {
                        if (i++ >= 3)
                            break;
                        if (speed < 1000 * 1000)
                            sb.append(getString(R.string.msg_kbsec, speed / 1000));
                        else
                            sb.append(getString(R.string.msg_mbsec, speed / 1000 / 1000));
                        sb.append(' ');
                        List<String> apps = Util.getApplicationNames(mapSpeedUid.get(speed), ServiceSinkhole.this);
                        sb.append(apps.size() > 0 ? apps.get(0) : "?");
                        sb.append("\r\n");
                    }
                    if (sb.length() > 0)
                        sb.setLength(sb.length() - 2);
                    remoteViews.setTextViewText(R.id.tvTop, sb.toString());
                }
            }

            t = ct;
            tx = ttx;
            rx = trx;

            // Create bitmap
            int height = Util.dips2pixels(96, ServiceSinkhole.this);
            int width = Util.dips2pixels(96 * 5, ServiceSinkhole.this);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            // Create canvas
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.TRANSPARENT);

            // Determine max
            float max = 0;
            long xmax = 0;
            float ymax = 0;
            for (int i = 0; i < gt.size(); i++) {
                long t = gt.get(i);
                float tx = gtx.get(i);
                float rx = grx.get(i);
                if (t > xmax)
                    xmax = t;
                if (tx > max)
                    max = tx;
                if (rx > max)
                    max = rx;
                if (tx > ymax)
                    ymax = tx;
                if (rx > ymax)
                    ymax = rx;
            }

            // Build paths
            Path ptx = new Path();
            Path prx = new Path();
            for (int i = 0; i < gtx.size(); i++) {
                float x = width - width * (xmax - gt.get(i)) / 1000f / samples;
                float ytx = height - height * gtx.get(i) / ymax;
                float yrx = height - height * grx.get(i) / ymax;
                if (i == 0) {
                    ptx.moveTo(x, ytx);
                    prx.moveTo(x, yrx);
                } else {
                    ptx.lineTo(x, ytx);
                    prx.lineTo(x, yrx);
                }
            }

            // Build paint
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);

            // Draw scale line
            paint.setStrokeWidth(Util.dips2pixels(1, ServiceSinkhole.this));
            paint.setColor(ContextCompat.getColor(ServiceSinkhole.this, R.color.colorGrayed));
            float y = height / 2;
            canvas.drawLine(0, y, width, y, paint);

            // Draw paths
            paint.setStrokeWidth(Util.dips2pixels(2, ServiceSinkhole.this));
            paint.setColor(ContextCompat.getColor(ServiceSinkhole.this, R.color.colorSend));
            canvas.drawPath(ptx, paint);
            paint.setColor(ContextCompat.getColor(ServiceSinkhole.this, R.color.colorReceive));
            canvas.drawPath(prx, paint);

            // Update remote view
            remoteViews.setImageViewBitmap(R.id.ivTraffic, bitmap);
            if (txsec < 1000 * 1000)
                remoteViews.setTextViewText(R.id.tvTx, getString(R.string.msg_kbsec, txsec / 1000));
            else
                remoteViews.setTextViewText(R.id.tvTx, getString(R.string.msg_mbsec, txsec / 1000 / 1000));

            if (rxsec < 1000 * 1000)
                remoteViews.setTextViewText(R.id.tvRx, getString(R.string.msg_kbsec, rxsec / 1000));
            else
                remoteViews.setTextViewText(R.id.tvRx, getString(R.string.msg_mbsec, rxsec / 1000 / 1000));

            if (max < 1000 * 1000)
                remoteViews.setTextViewText(R.id.tvMax, getString(R.string.msg_kbsec, max / 2 / 1000));
            else
                remoteViews.setTextViewText(R.id.tvMax, getString(R.string.msg_mbsec, max / 2 / 1000 / 1000));

            // Show session/file count
            if (filter && loglevel <= Log.WARN) {
                int[] count = jni_get_stats(jni_context);
                remoteViews.setTextViewText(R.id.tvSessions, count[0] + "/" + count[1] + "/" + count[2]);
                remoteViews.setTextViewText(R.id.tvFiles, count[3] + "/" + count[4]);
            } else {
                remoteViews.setTextViewText(R.id.tvSessions, "");
                remoteViews.setTextViewText(R.id.tvFiles, "");
            }

            // Show notification
            Intent main = new Intent(ServiceSinkhole.this, ActivityMain.class);
            PendingIntent pi = PendingIntent.getActivity(ServiceSinkhole.this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(ServiceSinkhole.this, "notify");
            builder.setWhen(when)
                    .setSmallIcon(R.drawable.ic_equalizer_white_24dp)
                    .setContent(remoteViews)
                    .setContentIntent(pi)
                    .setColor(getResources().getColor(R.color.colorTrackerControl))
                    .setOngoing(true)
                    .setAutoCancel(false);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

            if (state == State.none || state == State.waiting) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=" + state.toString());
                    stopForeground(true);
                }
                startForeground(NOTIFY_TRAFFIC, builder.build());
                state = State.stats;
                Log.d(TAG, "Start foreground state=" + state.toString());
            } else
                NotificationManagerCompat.from(ServiceSinkhole.this).notify(NOTIFY_TRAFFIC, builder.build());
        }
    }

    public static List<InetAddress> getDns(Context context) {
        List<InetAddress> listDns = new ArrayList<>();
        List<String> sysDns = Util.getDefaultDNS(context);

        // Get custom DNS servers
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean ip6 = prefs.getBoolean("ip6", true);
        boolean filter = prefs.getBoolean("filter", true);
        String vpnDns1 = prefs.getString("dns", null);
        String vpnDns2 = prefs.getString("dns2", null);
        Log.i(TAG, "DNS system=" + TextUtils.join(",", sysDns) + " config=" + vpnDns1 + "," + vpnDns2);

        if (vpnDns1 != null)
            try {
                InetAddress dns = InetAddress.getByName(vpnDns1);
                if (!(dns.isLoopbackAddress() || dns.isAnyLocalAddress()) &&
                        (dns instanceof Inet4Address))
                    listDns.add(dns);
            } catch (Throwable ignored) {
            }

        if (vpnDns2 != null)
            try {
                InetAddress dns = InetAddress.getByName(vpnDns2);
                if (!(dns.isLoopbackAddress() || dns.isAnyLocalAddress()) &&
                        (dns instanceof Inet4Address))
                    listDns.add(dns);
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }

        if (listDns.size() == 2)
            return listDns;

        for (String def_dns : sysDns)
            try {
                InetAddress ddns = InetAddress.getByName(def_dns);
                if (!listDns.contains(ddns) &&
                        !(ddns.isLoopbackAddress() || ddns.isAnyLocalAddress()) &&
                        (ddns instanceof Inet4Address))
                    listDns.add(ddns);
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }

        // Remove local DNS servers when not routing LAN
        int count = listDns.size();
        boolean lan = prefs.getBoolean("lan", false);
        //boolean use_hosts = prefs.getBoolean("use_hosts", false);
        if (lan
                //&& use_hosts
                && filter)
            try {
                List<Pair<InetAddress, Integer>> subnets = new ArrayList<>();
                subnets.add(new Pair<>(InetAddress.getByName("10.0.0.0"), 8));
                subnets.add(new Pair<>(InetAddress.getByName("172.16.0.0"), 12));
                subnets.add(new Pair<>(InetAddress.getByName("192.168.0.0"), 16));

                for (Pair<InetAddress, Integer> subnet : subnets) {
                    InetAddress hostAddress = subnet.first;
                    BigInteger host = new BigInteger(1, hostAddress.getAddress());

                    int prefix = subnet.second;
                    BigInteger mask = BigInteger.valueOf(-1).shiftLeft(hostAddress.getAddress().length * 8 - prefix);

                    for (InetAddress dns : new ArrayList<>(listDns))
                        if (hostAddress.getAddress().length == dns.getAddress().length) {
                            BigInteger ip = new BigInteger(1, dns.getAddress());

                            if (host.and(mask).equals(ip.and(mask))) {
                                Log.i(TAG, "Local DNS server host=" + hostAddress + "/" + prefix + " dns=" + dns);
                                listDns.remove(dns);
                            }
                        }
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }

        // Always set DNS servers
        if (listDns.size() == 0 || listDns.size() < count)
            try {
                listDns.add(InetAddress.getByName("9.9.9.9"));
                listDns.add(InetAddress.getByName("149.112.112.112"));
                if (ip6) {
                    listDns.add(InetAddress.getByName("2620:fe::fe"));
                    listDns.add(InetAddress.getByName("2620:fe::9"));
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }

        Log.i(TAG, "Get DNS=" + TextUtils.join(",", listDns));

        return listDns;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private ParcelFileDescriptor startVPN(Builder builder) throws SecurityException {
        try {
            ParcelFileDescriptor pfd = builder.establish();

            // Set underlying network
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                Network active = (cm == null ? null : cm.getActiveNetwork());
                if (active != null) {
                    Log.i(TAG, "Setting underlying network=" + cm.getNetworkInfo(active));
                    setUnderlyingNetworks(new Network[]{active});
                }
            }

            return pfd;
        } catch (SecurityException ex) {
            throw ex;
        } catch (Throwable ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            return null;
        }
    }

    private Builder getBuilder(List<Rule> listAllowed, List<Rule> listRule) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean subnet = prefs.getBoolean("subnet", false);
        boolean tethering = prefs.getBoolean("tethering", false);
        boolean lan = prefs.getBoolean("lan", false);
        boolean ip6 = prefs.getBoolean("ip6", true);
        boolean filter = prefs.getBoolean("filter", true);
        boolean system = prefs.getBoolean("manage_system", false);

        // Build VPN service
        Builder builder = new Builder();
        builder.setSession(getString(R.string.app_name));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            builder.setMetered(Util.isMeteredNetwork(this));

        // VPN address
        String vpn4 = prefs.getString("vpn4", "10.1.10.1");
        Log.i(TAG, "Using VPN4=" + vpn4);
        builder.addAddress(vpn4, 32);
        if (ip6) {
            String vpn6 = prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1");
            Log.i(TAG, "Using VPN6=" + vpn6);
            builder.addAddress(vpn6, 128);
        }

        // DNS address
        if (filter)
            for (InetAddress dns : getDns(ServiceSinkhole.this)) {
                if (ip6 || dns instanceof Inet4Address) {
                    Log.i(TAG, "Using DNS=" + dns);
                    builder.addDnsServer(dns);
                }
            }

        // Subnet routing
        if (subnet) {
            // Exclude IP ranges
            List<IPUtil.CIDR> listExclude = new ArrayList<>();
            listExclude.add(new IPUtil.CIDR("127.0.0.0", 8)); // localhost

            if (tethering && !lan) {
                // USB tethering 192.168.42.x
                // Wi-Fi tethering 192.168.43.x
                listExclude.add(new IPUtil.CIDR("192.168.42.0", 23));
                // Bluetooth tethering 192.168.44.x
                listExclude.add(new IPUtil.CIDR("192.168.44.0", 24));
                // Wi-Fi direct 192.168.49.x
                listExclude.add(new IPUtil.CIDR("192.168.49.0", 24));
            }

            if (lan) {
                // https://tools.ietf.org/html/rfc1918
                listExclude.add(new IPUtil.CIDR("10.0.0.0", 8));
                listExclude.add(new IPUtil.CIDR("172.16.0.0", 12));
                listExclude.add(new IPUtil.CIDR("192.168.0.0", 16));
            }

            if (!filter) {
                for (InetAddress dns : getDns(ServiceSinkhole.this))
                    if (dns instanceof Inet4Address)
                        listExclude.add(new IPUtil.CIDR(dns.getHostAddress(), 32));

                String dns_specifier = Util.getPrivateDnsSpecifier(ServiceSinkhole.this);
                if (!TextUtils.isEmpty(dns_specifier))
                    try {
                        Log.i(TAG, "Resolving private dns=" + dns_specifier);
                        for (InetAddress pdns : InetAddress.getAllByName(dns_specifier))
                            if (pdns instanceof Inet4Address)
                                listExclude.add(new IPUtil.CIDR(pdns.getHostAddress(), 32));
                    } catch (Throwable ex) {
                        Log.e(TAG, ex.toString());
                    }
            }

            // https://en.wikipedia.org/wiki/Mobile_country_code
            Configuration config = getResources().getConfiguration();

            // T-Mobile Wi-Fi calling
            if (config.mcc == 310 && (config.mnc == 160 ||
                    config.mnc == 200 ||
                    config.mnc == 210 ||
                    config.mnc == 220 ||
                    config.mnc == 230 ||
                    config.mnc == 240 ||
                    config.mnc == 250 ||
                    config.mnc == 260 ||
                    config.mnc == 270 ||
                    config.mnc == 310 ||
                    config.mnc == 490 ||
                    config.mnc == 660 ||
                    config.mnc == 800)) {
                listExclude.add(new IPUtil.CIDR("66.94.2.0", 24));
                listExclude.add(new IPUtil.CIDR("66.94.6.0", 23));
                listExclude.add(new IPUtil.CIDR("66.94.8.0", 22));
                listExclude.add(new IPUtil.CIDR("208.54.0.0", 16));
            }

            // Verizon wireless calling
            if ((config.mcc == 310 &&
                    (config.mnc == 4 ||
                            config.mnc == 5 ||
                            config.mnc == 6 ||
                            config.mnc == 10 ||
                            config.mnc == 12 ||
                            config.mnc == 13 ||
                            config.mnc == 350 ||
                            config.mnc == 590 ||
                            config.mnc == 820 ||
                            config.mnc == 890 ||
                            config.mnc == 910)) ||
                    (config.mcc == 311 && (config.mnc == 12 ||
                            config.mnc == 110 ||
                            (config.mnc >= 270 && config.mnc <= 289) ||
                            config.mnc == 390 ||
                            (config.mnc >= 480 && config.mnc <= 489) ||
                            config.mnc == 590)) ||
                    (config.mcc == 312 && (config.mnc == 770))) {
                listExclude.add(new IPUtil.CIDR("66.174.0.0", 16)); // 66.174.0.0 - 66.174.255.255
                listExclude.add(new IPUtil.CIDR("66.82.0.0", 15)); // 69.82.0.0 - 69.83.255.255
                listExclude.add(new IPUtil.CIDR("69.96.0.0", 13)); // 69.96.0.0 - 69.103.255.255
                listExclude.add(new IPUtil.CIDR("70.192.0.0", 11)); // 70.192.0.0 - 70.223.255.255
                listExclude.add(new IPUtil.CIDR("97.128.0.0", 9)); // 97.128.0.0 - 97.255.255.255
                listExclude.add(new IPUtil.CIDR("174.192.0.0", 9)); // 174.192.0.0 - 174.255.255.255
                listExclude.add(new IPUtil.CIDR("72.96.0.0", 9)); // 72.96.0.0 - 72.127.255.255
                listExclude.add(new IPUtil.CIDR("75.192.0.0", 9)); // 75.192.0.0 - 75.255.255.255
                listExclude.add(new IPUtil.CIDR("97.0.0.0", 10)); // 97.0.0.0 - 97.63.255.255
            }

            // Broadcast
            listExclude.add(new IPUtil.CIDR("224.0.0.0", 3));

            Collections.sort(listExclude);

            try {
                InetAddress start = InetAddress.getByName("0.0.0.0");
                for (IPUtil.CIDR exclude : listExclude) {
                    Log.i(TAG, "Exclude " + exclude.getStart().getHostAddress() + "..." + exclude.getEnd().getHostAddress());
                    for (IPUtil.CIDR include : IPUtil.toCIDR(start, IPUtil.minus1(exclude.getStart())))
                        try {
                            builder.addRoute(include.address, include.prefix);
                        } catch (Throwable ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                    start = IPUtil.plus1(exclude.getEnd());
                }
                String end = (lan ? "255.255.255.254" : "255.255.255.255");
                for (IPUtil.CIDR include : IPUtil.toCIDR("224.0.0.0", end))
                    try {
                        builder.addRoute(include.address, include.prefix);
                    } catch (Throwable ex) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    }
            } catch (UnknownHostException ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
        } else
            builder.addRoute("0.0.0.0", 0);

        Log.i(TAG, "IPv6=" + ip6);
        if (ip6)
            builder.addRoute("2000::", 3); // unicast

        // MTU
        int mtu = jni_get_mtu();
        Log.i(TAG, "MTU=" + mtu);
        builder.setMtu(mtu);

        // Add list of allowed applications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
            if (last_connected && !filter)
                for (Rule rule : listAllowed)
                    try {
                        builder.addDisallowedApplication(rule.packageName);
                    } catch (PackageManager.NameNotFoundException ex) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    }
            else if (filter)
                for (Rule rule : listRule)
                    if (!rule.apply || (!system && rule.system))
                        try {
                            Log.i(TAG, "Not routing " + rule.packageName);
                            builder.addDisallowedApplication(rule.packageName);
                        } catch (PackageManager.NameNotFoundException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
        }

        // Build configure intent
        Intent configure = new Intent(this, ActivityMain.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, configure, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        builder.setConfigureIntent(pi);

        return builder;
    }

    private void startNative(final ParcelFileDescriptor vpn, List<Rule> listAllowed, List<Rule> listRule) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
        boolean log = prefs.getBoolean("log", false);
        boolean log_app = prefs.getBoolean("log_app", true);
        boolean filter = prefs.getBoolean("filter", true);

        Log.i(TAG, "Start native log=" + log + "/" + log_app + " filter=" + filter);

        // Prepare rules
        if (filter) {
            prepareUidAllowed(listAllowed, listRule);
            prepareHostsBlocked(ServiceSinkhole.this);
            prepareUidIPFilters(null);
            prepareForwarding();
        } else {
            lock.writeLock().lock();
            mapUidAllowed.clear();
            mapUidKnown.clear();
            mapHostsBlocked.clear();
            mapUidIPFilters.clear();
            mapForward.clear();
            lock.writeLock().unlock();
        }

        if (log || log_app || filter) {
            int prio = Integer.parseInt(prefs.getString("loglevel", Integer.toString(Log.WARN)));
            final int rcode = Integer.parseInt(prefs.getString("rcode", "3"));
            if (prefs.getBoolean("socks5_enabled", false))
                jni_socks5(
                        prefs.getString("socks5_addr", ""),
                        Integer.parseInt(prefs.getString("socks5_port", "0")),
                        prefs.getString("socks5_username", ""),
                        prefs.getString("socks5_password", ""));
            else
                jni_socks5("", 0, "", "");

            if (tunnelThread == null) {
                Log.i(TAG, "Starting tunnel thread context=" + jni_context);
                jni_start(jni_context, prio);

                tunnelThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Log.i(TAG, "Running tunnel context=" + jni_context);
                        jni_run(jni_context, vpn.getFd(), mapForward.containsKey(53), rcode);
                        Log.i(TAG, "Tunnel exited");
                        tunnelThread = null;
                    }
                });
                //tunnelThread.setPriority(Thread.MAX_PRIORITY);
                tunnelThread.start();

                Log.i(TAG, "Started tunnel thread");
            }
        }
    }

    private void stopNative(ParcelFileDescriptor vpn) {
        Log.i(TAG, "Stop native");

        if (tunnelThread != null) {
            Log.i(TAG, "Stopping tunnel thread");

            jni_stop(jni_context);

            Thread thread = tunnelThread;
            while (thread != null && thread.isAlive()) {
                try {
                    Log.i(TAG, "Joining tunnel thread context=" + jni_context);
                    thread.join();
                } catch (InterruptedException ignored) {
                    Log.i(TAG, "Joined tunnel interrupted");
                }
                thread = tunnelThread;
            }
            tunnelThread = null;

            jni_clear(jni_context);

            Log.i(TAG, "Stopped tunnel thread");
        }
    }

    private void unprepare() {
        lock.writeLock().lock();
        mapUidAllowed.clear();
        mapUidKnown.clear();
        mapHostsBlocked.clear();
        mapUidIPFilters.clear();
        mapForward.clear();
        lock.writeLock().unlock();
    }

    private void prepareUidAllowed(List<Rule> listAllowed, List<Rule> listRule) {
        lock.writeLock().lock();

        mapUidAllowed.clear();
        for (Rule rule : listAllowed)
            mapUidAllowed.put(rule.uid, true);

        mapUidKnown.clear();
        for (Rule rule : listRule)
            mapUidKnown.put(rule.uid, rule.uid);

        lock.writeLock().unlock();
    }

    public static void prepareHostsBlocked(Context c) {
        BufferedReader br = null;
        InputStreamReader is = null;
        File hosts = new File(c.getFilesDir(), "hosts.txt");

        try {
            if (!hosts.exists() || !hosts.canRead()) {
                if (mapHostsBlocked.size() > 0) {
                    Log.i(TAG, "Hosts file unchanged");
                    return;
                }
                is = new InputStreamReader(c.getAssets().open("hosts.txt"));
            } else {
                boolean changed = (hosts.lastModified() != last_hosts_modified);
                if (!changed && mapHostsBlocked.size() > 0) {
                    Log.i(TAG, "Hosts file unchanged");
                    return;
                }
                last_hosts_modified = hosts.lastModified();
                is = new FileReader(hosts);
            }

            lock.writeLock().lock();
            mapHostsBlocked.clear();

            int count = 0;
            br = new BufferedReader(is);
            String line;
            while ((line = br.readLine()) != null) {
                int hash = line.indexOf('#');
                if (hash >= 0)
                    line = line.substring(0, hash);
                line = line.trim();
                if (line.length() > 0) {
                    String[] words = line.split("\\s+");
                    if (words.length == 2) {
                        count++;
                        mapHostsBlocked.put(words[1], true);
                    } else
                        Log.i(TAG, "Invalid hosts file line: " + line);
                }
            }
            mapHostsBlocked.put("test.netguard.me", true);
            Log.i(TAG, count + " hosts read");
        } catch (IOException ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        } finally {
            if (br != null)
                try {
                    br.close();
                } catch (IOException exex) {
                    Log.e(TAG, exex.toString() + "\n" + Log.getStackTraceString(exex));
                }
            if (is != null)
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }

        lock.writeLock().unlock();
    }

    private void prepareUidIPFilters(String dname) {
        SharedPreferences lockdown = getSharedPreferences("lockdown", Context.MODE_PRIVATE);

        lock.writeLock().lock();

        if (dname == null) // reset mechanism, called from startNative()
            mapUidIPFilters.clear();

        try (Cursor cursor = DatabaseHelper.getInstance(ServiceSinkhole.this).getAccessDns(dname)) {
            int colUid = cursor.getColumnIndex("uid");
            int colVersion = cursor.getColumnIndex("version");
            int colProtocol = cursor.getColumnIndex("protocol");
            int colDAddr = cursor.getColumnIndex("daddr");
            int colResource = cursor.getColumnIndex("resource");
            int colDPort = cursor.getColumnIndex("dport");
            int colBlock = cursor.getColumnIndex("block");
            int colTime = cursor.getColumnIndex("time");
            int colTTL = cursor.getColumnIndex("ttl");
            while (cursor.moveToNext()) {
                int uid = cursor.getInt(colUid);
                int version = cursor.getInt(colVersion);
                int protocol = cursor.getInt(colProtocol);
                String daddr = cursor.getString(colDAddr);
                String dresource = (cursor.isNull(colResource) ? null : cursor.getString(colResource));
                int dport = cursor.getInt(colDPort);
                boolean block = (cursor.getInt(colBlock) > 0);
                long time = (cursor.isNull(colTime) ? new Date().getTime() : cursor.getLong(colTime));
                long ttl = (cursor.isNull(colTTL) ? 7 * 24 * 3600 * 1000L : cursor.getLong(colTTL));

                if (isLockedDown(last_metered)) {
                    String[] pkg = getPackageManager().getPackagesForUid(uid);
                    if (pkg != null && pkg.length > 0) {
                        if (!lockdown.getBoolean(pkg[0], false))
                            continue;
                    }
                }

                IPKey key = new IPKey(version, protocol, dport, uid);
                synchronized (mapUidIPFilters) {
                    if (!mapUidIPFilters.containsKey(key))
                        mapUidIPFilters.put(key, new HashMap());

                    try {
                        String name = (dresource == null ? daddr : dresource);

                        // Firewall operates on IP layer, so we need numeric IP
                        if (Util.isNumericAddress(name)) {
                            InetAddress iname = InetAddress.getByName(name);
                            if (version == 4 && !(iname instanceof Inet4Address))
                                continue;
                            if (version == 6 && !(iname instanceof Inet6Address))
                                continue;

                            boolean exists = mapUidIPFilters.get(key).containsKey(iname);
                            if (!exists || !mapUidIPFilters.get(key).get(iname).isBlocked()) {
                                IPRule rule = new IPRule(key, name + "/" + iname, block, time + ttl);
                                mapUidIPFilters.get(key).put(iname, rule);
                                if (exists)
                                    Log.w(TAG, "Address conflict " + key + " " + daddr + "/" + dresource);
                            } else if (exists) {
                                mapUidIPFilters.get(key).get(iname).updateExpires(time + ttl);
                                if (dname != null && ttl > 60 * 1000L)
                                    Log.w(TAG, "Address updated " + key + " " + daddr + "/" + dresource);
                            } else {
                                if (dname != null)
                                    Log.i(TAG, "Ignored " + key + " " + daddr + "/" + dresource + "=" + block);
                            }
                        } else
                            Log.w(TAG, "Address not numeric " + name);
                    } catch (UnknownHostException ex) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                    }
                }
            }
        }

        lock.writeLock().unlock();
    }

    private void prepareForwarding() {
        lock.writeLock().lock();
        mapForward.clear();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("filter", true)) {
            try (Cursor cursor = DatabaseHelper.getInstance(ServiceSinkhole.this).getForwarding()) {
                int colProtocol = cursor.getColumnIndex("protocol");
                int colDPort = cursor.getColumnIndex("dport");
                int colRAddr = cursor.getColumnIndex("raddr");
                int colRPort = cursor.getColumnIndex("rport");
                int colRUid = cursor.getColumnIndex("ruid");
                while (cursor.moveToNext()) {
                    Forward fwd = new Forward();
                    fwd.protocol = cursor.getInt(colProtocol);
                    fwd.dport = cursor.getInt(colDPort);
                    fwd.raddr = cursor.getString(colRAddr);
                    fwd.rport = cursor.getInt(colRPort);
                    fwd.ruid = cursor.getInt(colRUid);
                    mapForward.put(fwd.dport, fwd);
                    Log.i(TAG, "Forward " + fwd);
                }
            }
        }
        lock.writeLock().unlock();
    }

    private boolean isLockedDown(boolean metered) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
        boolean lockdown = prefs.getBoolean("lockdown", false);
        boolean lockdown_wifi = prefs.getBoolean("lockdown_wifi", true);
        boolean lockdown_other = prefs.getBoolean("lockdown_other", true);
        if (metered ? !lockdown_other : !lockdown_wifi)
            lockdown = false;

        return lockdown;
    }

    private List<Rule> getAllowedRules(List<Rule> listRule) {
        List<Rule> listAllowed = new ArrayList<>();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Check state
        boolean wifi = Util.isWifiActive(this);
        boolean metered = Util.isMeteredNetwork(this);
        boolean useMetered = prefs.getBoolean("use_metered", false);
        String ssidNetwork = Util.getWifiSSID(this);
        String generation = Util.getNetworkGeneration(this);
        boolean unmetered_2g = prefs.getBoolean("unmetered_2g", false);
        boolean unmetered_3g = prefs.getBoolean("unmetered_3g", false);
        boolean unmetered_4g = prefs.getBoolean("unmetered_4g", false);
        boolean roaming = Util.isRoaming(ServiceSinkhole.this);
        boolean national = prefs.getBoolean("national_roaming", false);
        boolean eu = prefs.getBoolean("eu_roaming", false);
        boolean tethering = prefs.getBoolean("tethering", false);
        boolean filter = prefs.getBoolean("filter", true);

        // Update connected state
        last_connected = Util.isConnected(ServiceSinkhole.this);

        boolean org_metered = metered;
        boolean org_roaming = roaming;

        // Update metered state
        if (wifi && !useMetered)
            metered = false;
        if (unmetered_2g && "2G".equals(generation))
            metered = false;
        if (unmetered_3g && "3G".equals(generation))
            metered = false;
        if (unmetered_4g && "4G".equals(generation))
            metered = false;
        last_metered = metered;

        boolean lockdown = isLockedDown(last_metered);

        // Update roaming state
        if (roaming && eu)
            roaming = !Util.isEU(this);
        if (roaming && national)
            roaming = !Util.isNational(this);

        Log.i(TAG, "Get allowed" +
                " connected=" + last_connected +
                " wifi=" + wifi +
                " network=" + ssidNetwork +
                " metered=" + metered + "/" + org_metered +
                " generation=" + generation +
                " roaming=" + roaming + "/" + org_roaming +
                " interactive=" + last_interactive +
                " tethering=" + tethering +
                " filter=" + filter +
                " lockdown=" + lockdown);

        if (last_connected)
            for (Rule rule : listRule) {
                boolean blocked = (metered ? rule.other_blocked : rule.wifi_blocked);
                boolean screen = (metered ? rule.screen_other : rule.screen_wifi);
                if ((!blocked || (screen && last_interactive)) &&
                        (!metered || !(rule.roaming && roaming)) &&
                        (!lockdown || rule.lockdown))
                    listAllowed.add(rule);
            }

        Log.i(TAG, "Allowed " + listAllowed.size() + " of " + listRule.size());
        return listAllowed;
    }

    private void stopVPN(ParcelFileDescriptor pfd) {
        Log.i(TAG, "Stopping");
        try {
            pfd.close();
        } catch (IOException ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }
    }

    // Called from native code
    private void nativeExit(String reason) {
        Log.w(TAG, "Native exit reason=" + reason);
        if (reason != null) {
            showErrorNotification(reason);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            prefs.edit().putBoolean("enabled", false).apply();
            WidgetMain.updateWidgets(this);
        }
    }

    // Called from native code
    private void nativeError(int error, String message) {
        Log.w(TAG, "Native error " + error + ": " + message);
        showErrorNotification(message);
    }

    // Called from native code
    private void logPacket(Packet packet) {
        logHandler.queue(packet);
    }

    // Called from native code
    private void dnsResolved(ResourceRecord rr) {
        if (DatabaseHelper.getInstance(ServiceSinkhole.this).insertDns(rr)) {
            Log.i(TAG, "New IP " + rr);
            prepareUidIPFilters(rr.QName);

            if (Util.isNumericAddress(rr.Resource)) { // make sure correct format
                ipToHost.remove(rr.Resource);
                ipToTracker.remove(rr.Resource);
            }
        }
    }

    // Called from native code
    private boolean isDomainBlocked(String name) {
        return false;
    }

    // Called from native code
    @TargetApi(Build.VERSION_CODES.Q)
    private int getUidQ(int version, int protocol, String saddr, int sport, String daddr, int dport) {
        if (protocol != 6 /* TCP */ && protocol != 17 /* UDP */)
            return Process.INVALID_UID;

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null)
            return Process.INVALID_UID;

        InetSocketAddress local = new InetSocketAddress(saddr, sport);
        InetSocketAddress remote = new InetSocketAddress(daddr, dport);

        Log.i(TAG, "Get uid local=" + local + " remote=" + remote);
        int uid = cm.getConnectionOwnerUid(protocol, local, remote);
        Log.i(TAG, "Get uid=" + uid);
        return uid;
    }

    private boolean isSupported(int protocol) {
        return (protocol == 1 /* ICMPv4 */ ||
                protocol == 58 /* ICMPv6 */ ||
                protocol == 6 /* TCP */ ||
                protocol == 17 /* UDP */);
    }

    static ConcurrentHashMap<Integer, String> uidToApp = new ConcurrentHashMap<>();
    static ConcurrentHashMap<String, Expiring<String>> ipToHost = new ConcurrentHashMap<>();
    static ConcurrentHashMap<String, Expiring<Tracker>> ipToTracker = new ConcurrentHashMap<>();
    static String NO_DNAME = "null"; // use a String, unequal the real null
    static Tracker NO_TRACKER = new Tracker(null, null, 0);

    // Called from native code
    private Allowed isAddressAllowed(Packet packet) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        lock.readLock().lock();

        packet.allowed = false;
        if (prefs.getBoolean("filter", true)) {
            // https://android.googlesource.com/platform/system/core/+/master/include/private/android_filesystem_config.h
            if (packet.protocol == 17 /* UDP */ && !prefs.getBoolean("filter_udp", true)) {
                // Allow unfiltered UDP
                packet.allowed = true;
                Log.i(TAG, "Allowing UDP " + packet);
            } else if (packet.uid < 2000 &&
                    !mapUidKnown.containsKey(packet.uid) && isSupported(packet.protocol)) {
                // Allow unknown system traffic
                packet.allowed = true;
                Log.w(TAG, "Allowing unknown system " + packet);
            } else if (packet.uid == Process.myUid()) {
                // Allow self
                packet.allowed = true;
                Log.w(TAG, "Allowing self " + packet);
            } else {
                boolean filtered = false;

                if (packet.data != null && !packet.data.isEmpty())
                    Log.d(TAG, "Found SNI in isAddressAllowed: " + packet.data);

                // Check if tracker is known
                if ((!Util.isPlayStoreInstall()
                        || prefs.getBoolean("log_logcat", false))
                    && blockKnownTracker(packet.daddr, packet.uid)) {
                        filtered = true;
                        packet.allowed = false;
                }

                InternetBlocklist internetBlocklist = InternetBlocklist.getInstance(ServiceSinkhole.this);
                if (internetBlocklist.blockedInternet(packet.uid)) {
                    filtered = true;
                    packet.allowed = false;
                }

	            if (!filtered)
                    packet.allowed = true;
            }
        }

        Allowed allowed = null;
        if (packet.allowed)
            if (mapForward.containsKey(packet.dport)) {
                Forward fwd = mapForward.get(packet.dport);
                if (fwd.ruid == packet.uid) {
                    allowed = new Allowed();
                } else {
                    allowed = new Allowed(fwd.raddr, fwd.rport);
                    packet.data = "> " + fwd.raddr + "/" + fwd.rport;
                }
            } else
                allowed = new Allowed();

        lock.readLock().unlock();

        if (prefs.getBoolean("log", false) || prefs.getBoolean("log_app", true))
            if (packet.protocol != 6 /* TCP */ || !"".equals(packet.flags))
                if (packet.uid != Process.myUid())
                    logPacket(packet);

        return allowed;
    }

    private boolean blockKnownTracker(String daddr, int uid) {
        Tracker tracker = null;
        Expiring<Tracker> expiringTracker = ipToTracker.get(daddr);
        if (expiringTracker != null) {
            tracker = expiringTracker.getOrExpired();

            if (tracker == null) // expired
                ipToTracker.remove(daddr);
        }

        if (tracker == null) {
            // Check if IP known
            String dname = null;
            Expiring<String> expiringHost = ipToHost.get(daddr);
            if (expiringHost != null) {
                dname = expiringHost.getOrExpired();

                if (dname == null) // expired
                    ipToHost.remove(daddr);
            }

            if (dname == null) {
                // Retrieve dname from DB
                DatabaseHelper dh = DatabaseHelper.getInstance(ServiceSinkhole.this);
                long time;
                long ttl;
                try (Cursor lookup = dh.getQAName(uid, daddr, true)) {
                    time = new Date().getTime();
                    ttl = 7 * 24 * 3600 * 1000L;

                    // Loop through entries and pick the one that is related to tracking
                    if (lookup != null) {
                        while (lookup.moveToNext()) {
                            // Get DNS expiry details
                            int colTime = lookup.getColumnIndex("time");
                            int colTTL = lookup.getColumnIndex("ttl");
                            if (!lookup.isNull(colTime))
                                time = lookup.getLong(colTime);
                            if (!lookup.isNull(colTTL))
                                ttl = lookup.getLong(colTTL);

                            // Check tracker
                            String aname = lookup.getString(lookup.getColumnIndex("aname"));
                            dname = lookup.getString(lookup.getColumnIndex("qname"));
                            tracker = TrackerList.findTracker(dname);

                            // If no tracker found, try DNS uncloaking
                            if (tracker == null
                                    && aname != null) {
                                tracker = TrackerList.findTracker(aname);

                                if (tracker != null) {
                                    dname = aname;
                                    Log.d(TAG, "Uncloaked: " + dname + " -> " + aname);
                                }
                            }

                            // If tracker found, seek no further
                            if (tracker != null)
                                break;
                        }
                    }
                }

                // No success in finding dname or tracker?
                if (dname == null)
                    dname = NO_DNAME;
                if (tracker == null)
                    tracker = NO_TRACKER;

                // Save dname and tracker
                ipToHost.put(daddr, new Expiring<>(dname, time + ttl));
                ipToTracker.put(daddr, new Expiring<>(tracker, time + ttl));
            }

            // If we can't resolve domain, use IP-based blocklist as fallback
            if (dname == null)
                if (TrackerList.trackingIps.contains(daddr))
                    tracker = TrackerList.findTracker(daddr);
        }

        // Log or block?
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("log_logcat", false)) {
            String app = uidToApp.get(uid);
            if (app == null) {
                PackageManager pm = getPackageManager();
                app = Common.getAppName(pm, uid);
                uidToApp.put(uid, app);
            }
            assert tracker != null;
            Log.i("TC-Log", app + " " + daddr + " " + ipToHost.get(daddr) + " " + tracker.getName());
        } else {
            if (tracker != NO_TRACKER) {
                TrackerBlocklist b = TrackerBlocklist.getInstance(ServiceSinkhole.this);
                return tracker != null
                        && b.blockedTracker(uid, tracker);
            }
        }

        return false;
    }

    // Called from native code
    private void accountUsage(Usage usage) {
        logHandler.account(usage);
    }

    private BroadcastReceiver interactiveStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            executor.submit(new Runnable() {
                @Override
                public void run() {
                    AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                    Intent i = new Intent(ACTION_SCREEN_OFF_DELAYED);
                    i.setPackage(context.getPackageName());
                    PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    am.cancel(pi);

                    try {
                        last_interactive = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                        reload("interactive state changed", ServiceSinkhole.this, true);

                        // Start/stop stats
                        statsHandler.sendEmptyMessage(
                                Util.isInteractive(ServiceSinkhole.this) ? MSG_STATS_START : MSG_STATS_STOP);
                    } catch (Throwable ex) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
                            am.set(AlarmManager.RTC_WAKEUP, new Date().getTime() + 15 * 1000L, pi);
                        else
                            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, new Date().getTime() + 15 * 1000L, pi);
                    }
                }
            });
        }
    };

    private BroadcastReceiver userReceiver = new BroadcastReceiver() {
        @Override
        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            user_foreground = Intent.ACTION_USER_FOREGROUND.equals(intent.getAction());
            Log.i(TAG, "User foreground=" + user_foreground + " user=" + (Process.myUid() / 100000));

            if (user_foreground) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                if (prefs.getBoolean("enabled", false)) {
                    // Allow service of background user to stop
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                    }

                    start("foreground", ServiceSinkhole.this);
                }
            } else
                stop("background", ServiceSinkhole.this, true);
        }
    };

    private BroadcastReceiver idleStateReceiver = new BroadcastReceiver() {
        @Override
        @TargetApi(Build.VERSION_CODES.M)
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            Log.i(TAG, "device idle=" + pm.isDeviceIdleMode());

            // Reload rules when coming from idle mode
            if (!pm.isDeviceIdleMode())
                reload("idle state changed", ServiceSinkhole.this, false);
        }
    };

    private BroadcastReceiver connectivityChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Filter VPN connectivity changes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                int networkType = intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, ConnectivityManager.TYPE_DUMMY);
                if (networkType == ConnectivityManager.TYPE_VPN)
                    return;
            }

            // Reload rules
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);
            reload("connectivity changed", ServiceSinkhole.this, false);
        }
    };

    ConnectivityManager.NetworkCallback networkMonitorCallback = new ConnectivityManager.NetworkCallback() {
        private String TAG = "TrackerControl.Monitor";

        private Map<Network, Long> validated = new HashMap<>();

        // https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/connectivity/NetworkMonitor.java

        @Override
        public void onAvailable(Network network) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getNetworkInfo(network);
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            Log.i(TAG, "Available network " + network + " " + ni);
            Log.i(TAG, "Capabilities=" + capabilities);
            checkConnectivity(network, ni, capabilities);
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getNetworkInfo(network);
            Log.i(TAG, "New capabilities network " + network + " " + ni);
            Log.i(TAG, "Capabilities=" + capabilities);
            checkConnectivity(network, ni, capabilities);
        }

        @Override
        public void onLosing(Network network, int maxMsToLive) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getNetworkInfo(network);
            Log.i(TAG, "Losing network " + network + " within " + maxMsToLive + " ms " + ni);
        }

        @Override
        public void onLost(Network network) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getNetworkInfo(network);
            Log.i(TAG, "Lost network " + network + " " + ni);

            synchronized (validated) {
                validated.remove(network);
            }
        }

        @Override
        public void onUnavailable() {
            Log.i(TAG, "No networks available");
        }

        private void checkConnectivity(Network network, NetworkInfo ni, NetworkCapabilities capabilities) {
            if (ni != null && capabilities != null &&
                    ni.getDetailedState() != NetworkInfo.DetailedState.SUSPENDED &&
                    ni.getDetailedState() != NetworkInfo.DetailedState.BLOCKED &&
                    ni.getDetailedState() != NetworkInfo.DetailedState.DISCONNECTED &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {

                synchronized (validated) {
                    if (validated.containsKey(network) &&
                            validated.get(network) + 20 * 1000 > new Date().getTime()) {
                        Log.i(TAG, "Already validated " + network + " " + ni);
                        return;
                    }
                }

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                String host = prefs.getString("validate", "www.f-droid.org");
                Log.i(TAG, "Validating " + network + " " + ni + " host=" + host);

                Socket socket = null;
                try {
                    socket = network.getSocketFactory().createSocket();
                    socket.connect(new InetSocketAddress(host, 443), 10000);
                    Log.i(TAG, "Validated " + network + " " + ni + " host=" + host);
                    synchronized (validated) {
                        validated.put(network, new Date().getTime());
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                        cm.reportNetworkConnectivity(network, true);
                        Log.i(TAG, "Reported " + network + " " + ni);
                    }
                } catch (IOException ex) {
                    Log.e(TAG, ex.toString());
                    Log.i(TAG, "No connectivity " + network + " " + ni);
                } finally {
                    if (socket != null)
                        try {
                            socket.close();
                        } catch (IOException ex) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                        }
                }
            }
        }
    };

    private PhoneStateListener phoneStateListener = new PhoneStateListener() {
        private String last_generation = null;

        @Override
        public void onDataConnectionStateChanged(int state, int networkType) {
            if (state == TelephonyManager.DATA_CONNECTED) {
                String current_generation = Util.getNetworkGeneration(ServiceSinkhole.this);
                Log.i(TAG, "Data connected generation=" + current_generation);

                if (last_generation == null || !last_generation.equals(current_generation)) {
                    Log.i(TAG, "New network generation=" + current_generation);
                    last_generation = current_generation;

                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                    if (prefs.getBoolean("unmetered_2g", false) ||
                            prefs.getBoolean("unmetered_3g", false) ||
                            prefs.getBoolean("unmetered_4g", false))
                        reload("data connection state changed", ServiceSinkhole.this, false);
                }
            }
        }
    };

    private BroadcastReceiver packageChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Received " + intent);
            Util.logExtras(intent);

            try {
                if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                    // Application added
                    Rule.clearCache(context);

                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                        int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                        if (uid > -1) {
                            // Check strict blocking
                            TrackerBlocklist b = TrackerBlocklist.getInstance(context);
                            if (!prefs.getBoolean("strict_blocking", true))
                                b.unblock(uid, NECESSARY_CATEGORY);

                            // Show install notification
                            if (prefs.getBoolean("installed", true))
                                notifyNewApplication(uid, this);
                        }
                    }

                    reload("package added", context, false);

                } else if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                    // Application removed
                    Rule.clearCache(context);

                    if (intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, false)) {
                        // Remove settings
                        String packageName = intent.getData().getSchemeSpecificPart();
                        Log.i(TAG, "Deleting settings package=" + packageName);
                        context.getSharedPreferences("wifi", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("other", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("screen_wifi", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("screen_other", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("roaming", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("lockdown", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("apply", Context.MODE_PRIVATE).edit().remove(packageName).apply();
                        context.getSharedPreferences("notify", Context.MODE_PRIVATE).edit().remove(packageName).apply();

                        int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);
                        if (uid > 0) {
                            DatabaseHelper dh = DatabaseHelper.getInstance(context);
                            dh.clearLog(uid);
                            dh.clearAccess(uid, false);
                            uidToApp.remove(uid);

                            NotificationManagerCompat.from(context).cancel(uid); // installed notification
                            NotificationManagerCompat.from(context).cancel(uid + 10000); // access notification
                        }
                    }

                    reload("package deleted", context, false);
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }
        }
    };

    public void notifyNewApplication(int uid, BroadcastReceiver br) {
        if (uid < 0 || !Util.hasInternet(uid, this))
            return;

        try {
            // Get application name
            String name = TextUtils.join(", ", Util.getApplicationNames(uid, this));

            // Get application info
            PackageManager pm = getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages == null || packages.length < 1)
                throw new PackageManager.NameNotFoundException(Integer.toString(uid));
            boolean internet = Util.hasInternet(uid, this);
            final String packageName = packages[0];

            // Build notification
            Intent main = new Intent(this, DetailsActivity.class);
            main.putExtra(INTENT_EXTRA_APP_NAME, name);
            main.putExtra(INTENT_EXTRA_APP_PACKAGENAME, packageName);
            main.putExtra(INTENT_EXTRA_APP_UID, uid);
            PendingIntent pi = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "notify");
            builder.setSmallIcon(R.drawable.ic_rocket_white)
                    .setContentIntent(pi)
                    .addAction(0, getString(R.string.title_activity_detail), pi)
                    .setColor(getResources().getColor(R.color.colorTrackerControl))
                    .setAutoCancel(true);
            builder.setContentTitle(getString(R.string.msg_installed, name))
                    .setContentText(getString(R.string.msg_installed_description));

            // Add uninstall action
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + packageName));
            PendingIntent piUninstall = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            builder.addAction(0, getString(R.string.uninstall), piUninstall);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setVisibility(NotificationCompat.VISIBILITY_SECRET);

            // Show notification
            if (internet) {
                NotificationManagerCompat.from(this).notify(uid, builder.build());

                // Check tracker libraries in app
                if (br != null)
                    checkTrackers(packageName, uid, br, builder);
            }

        } catch (PackageManager.NameNotFoundException ex) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
        }
    }

    private void checkTrackers(String packageName, int uid, BroadcastReceiver br, NotificationCompat.Builder builder) {
        BroadcastReceiver.PendingResult result = br.goAsync();
        new Thread() {
            public void run() {
                try {
                    Context c = getApplicationContext();
                    TrackerLibraryAnalyser analyser = new TrackerLibraryAnalyser(c);
                    int trackerCount = StringUtils.countMatches(analyser.analyse(packageName), "•");
                    builder.setContentText(getString(R.string.msg_installed_tracker_libraries_found, trackerCount));
                    NotificationManagerCompat.from(c).notify(uid, builder.build());
                } catch (AnalysisException e) {
                    e.printStackTrace();
                }
                result.setResultCode(RESULT_OK);
                result.finish();
            }
        }.start();
    }

    @Override
    public void onCreate() {
        Log.i(TAG, "Create version=" + Util.getSelfVersionName(this) + "/" + Util.getSelfVersionCode(this));
        startForeground(NOTIFY_WAITING, getWaitingNotification());

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (jni_context != 0) {
            Log.w(TAG, "Create with context=" + jni_context);
            jni_stop(jni_context);
            synchronized (jni_lock) {
                jni_done(jni_context);
                jni_context = 0;
            }
        }

        // Native init
        jni_context = jni_init(Build.VERSION.SDK_INT);
        Log.i(TAG, "Created context=" + jni_context);
        boolean pcap = prefs.getBoolean("pcap", false);
        setPcap(pcap, this);

        Util.setTheme(this);
        super.onCreate();

        HandlerThread commandThread = new HandlerThread(getString(R.string.app_name) + " command", Process.THREAD_PRIORITY_FOREGROUND);
        HandlerThread logThread = new HandlerThread(getString(R.string.app_name) + " log", Process.THREAD_PRIORITY_BACKGROUND);
        HandlerThread statsThread = new HandlerThread(getString(R.string.app_name) + " stats", Process.THREAD_PRIORITY_BACKGROUND);
        commandThread.start();
        logThread.start();
        statsThread.start();

        commandLooper = commandThread.getLooper();
        logLooper = logThread.getLooper();
        statsLooper = statsThread.getLooper();

        commandHandler = new CommandHandler(commandLooper);
        logHandler = new LogHandler(logLooper);
        statsHandler = new StatsHandler(statsLooper);

        // Listen for user switches
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            IntentFilter ifUser = new IntentFilter();
            ifUser.addAction(Intent.ACTION_USER_BACKGROUND);
            ifUser.addAction(Intent.ACTION_USER_FOREGROUND);
            registerReceiver(userReceiver, ifUser);
            registeredUser = true;
        }

        // Listen for idle mode state changes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            IntentFilter ifIdle = new IntentFilter();
            ifIdle.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
            registerReceiver(idleStateReceiver, ifIdle);
            registeredIdleState = true;
        }

        // Listen for added/removed applications
        IntentFilter ifPackage = new IntentFilter();
        ifPackage.addAction(Intent.ACTION_PACKAGE_ADDED);
        ifPackage.addAction(Intent.ACTION_PACKAGE_REMOVED);
        ifPackage.addDataScheme("package");
        registerReceiver(packageChangedReceiver, ifPackage);
        registeredPackageChanged = true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            try {
                listenNetworkChanges();
            } catch (Throwable ex) {
                Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
                listenConnectivityChanges();
            }
        else
            listenConnectivityChanges();

        // Monitor networks
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                networkMonitorCallback);

        // Setup house holding
        Intent alarmIntent = new Intent(this, ServiceSinkhole.class);
        alarmIntent.setAction(ACTION_HOUSE_HOLDING);
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            pi = PendingIntent.getForegroundService(this, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        else
            pi = PendingIntent.getService(this, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.setInexactRepeating(AlarmManager.RTC, SystemClock.elapsedRealtime() + 60 * 1000, AlarmManager.INTERVAL_HALF_DAY, pi);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void listenNetworkChanges() {
        // Listen for network changes
        Log.i(TAG, "Starting listening to network changes");
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);

        ConnectivityManager.NetworkCallback nc = new ConnectivityManager.NetworkCallback() {
            private Boolean last_connected = null;
            private Boolean last_unmetered = null;
            private String last_generation = null;
            private List<InetAddress> last_dns = null;

            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "Available network=" + network);
                last_connected = Util.isConnected(ServiceSinkhole.this);
                reload("network available", ServiceSinkhole.this, false);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                Log.i(TAG, "Changed properties=" + network + " props=" + linkProperties);

                // Make sure the right DNS servers are being used
                List<InetAddress> dns = linkProperties.getDnsServers();
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? !same(last_dns, dns)
                        : prefs.getBoolean("reload_onconnectivity", false)) {
                    Log.i(TAG, "Changed link properties=" + linkProperties +
                            "DNS cur=" + TextUtils.join(",", dns) +
                            "DNS prv=" + (last_dns == null ? null : TextUtils.join(",", last_dns)));
                    last_dns = dns;
                    reload("link properties changed", ServiceSinkhole.this, false);
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                Log.i(TAG, "Changed capabilities=" + network + " caps=" + networkCapabilities);

                boolean connected = Util.isConnected(ServiceSinkhole.this);
                boolean unmetered = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                String generation = Util.getNetworkGeneration(ServiceSinkhole.this);
                Log.i(TAG, "Connected=" + connected + "/" + last_connected +
                        " unmetered=" + unmetered + "/" + last_unmetered +
                        " generation=" + generation + "/" + last_generation);

                if (last_connected != null && !last_connected.equals(connected))
                    reload("Connected state changed", ServiceSinkhole.this, false);

                if (last_unmetered != null && !last_unmetered.equals(unmetered))
                    reload("Unmetered state changed", ServiceSinkhole.this, false);

                if (last_generation != null && !last_generation.equals(generation)) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
                    if (prefs.getBoolean("unmetered_2g", false) ||
                            prefs.getBoolean("unmetered_3g", false) ||
                            prefs.getBoolean("unmetered_4g", false))
                        reload("Generation changed", ServiceSinkhole.this, false);
                }

                last_connected = connected;
                last_unmetered = unmetered;
                last_generation = generation;
            }

            @Override
            public void onLost(Network network) {
                Log.i(TAG, "Lost network=" + network);
                last_connected = Util.isConnected(ServiceSinkhole.this);
                reload("network lost", ServiceSinkhole.this, false);
            }

            boolean same(List<InetAddress> last, List<InetAddress> current) {
                if (last == null || current == null)
                    return false;
                if (last == null || last.size() != current.size())
                    return false;

                for (int i = 0; i < current.size(); i++)
                    if (!last.get(i).equals(current.get(i)))
                        return false;

                return true;
            }
        };
        cm.registerNetworkCallback(builder.build(), nc);
        networkCallback = nc;
    }

    private void listenConnectivityChanges() {
        // Listen for connectivity updates
        Log.i(TAG, "Starting listening to connectivity changes");
        IntentFilter ifConnectivity = new IntentFilter();
        ifConnectivity.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(connectivityChangedReceiver, ifConnectivity);
        registeredConnectivityChanged = true;

        // Listen for phone state changes
        Log.i(TAG, "Starting listening to service state changes");
        TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (tm != null) {
            tm.listen(phoneStateListener, PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);
            phone_state = true;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (state == State.enforcing)
            startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1));
        else
            startForeground(NOTIFY_WAITING, getWaitingNotification());

        Log.i(TAG, "Received " + intent);
        Util.logExtras(intent);

        // Check for set command
        if (intent != null && intent.hasExtra(EXTRA_COMMAND) &&
                intent.getSerializableExtra(EXTRA_COMMAND) == Command.set) {
            set(intent);
            return START_STICKY;
        }

        // Keep awake
        getLock(this).acquire();

        // Get state
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = prefs.getBoolean("enabled", false);

        // Handle service restart
        if (intent == null) {
            Log.i(TAG, "Restart");

            // Recreate intent
            intent = new Intent(this, ServiceSinkhole.class);
            intent.putExtra(EXTRA_COMMAND, enabled ? Command.start : Command.stop);
        }

        if (ACTION_HOUSE_HOLDING.equals(intent.getAction()))
            intent.putExtra(EXTRA_COMMAND, Command.householding);
        if (ACTION_WATCHDOG.equals(intent.getAction()))
            intent.putExtra(EXTRA_COMMAND, Command.watchdog);

        Command cmd = (Command) intent.getSerializableExtra(EXTRA_COMMAND);
        if (cmd == null)
            intent.putExtra(EXTRA_COMMAND, enabled ? Command.start : Command.stop);
        String reason = intent.getStringExtra(EXTRA_REASON);
        Log.i(TAG, "Start intent=" + intent + " command=" + cmd + " reason=" + reason +
                " vpn=" + (vpn != null) + " user=" + (Process.myUid() / 100000));

        commandHandler.queue(intent);

        return START_STICKY;
    }

    private void set(Intent intent) {
        // Get arguments
        int uid = intent.getIntExtra(EXTRA_UID, 0);
        String network = intent.getStringExtra(EXTRA_NETWORK);
        String pkg = intent.getStringExtra(EXTRA_PACKAGE);
        boolean blocked = intent.getBooleanExtra(EXTRA_BLOCKED, false);
        Log.i(TAG, "Set " + pkg + " " + network + "=" + blocked);

        // Get defaults
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
        boolean default_wifi = settings.getBoolean("whitelist_wifi", true);
        boolean default_other = settings.getBoolean("whitelist_other", true);

        // Update setting
        SharedPreferences prefs = getSharedPreferences(network, Context.MODE_PRIVATE);
        if (blocked == ("wifi".equals(network) ? default_wifi : default_other))
            prefs.edit().remove(pkg).apply();
        else
            prefs.edit().putBoolean(pkg, blocked).apply();

        // Apply rules
        ServiceSinkhole.reload("notification", ServiceSinkhole.this, false);

        // Update notification
        notifyNewApplication(uid, null);

        // Update UI
        Intent ruleset = new Intent(ActivityMain.ACTION_RULES_CHANGED);
        LocalBroadcastManager.getInstance(ServiceSinkhole.this).sendBroadcast(ruleset);
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "Revoke");

        // Disable firewall (will result in stop command)
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit().putBoolean("enabled", false).apply();

        // Feedback
        showDisabledNotification();
        WidgetMain.updateWidgets(this);

        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        synchronized (this) {
            Log.i(TAG, "Destroy");
            commandLooper.quit();
            logLooper.quit();
            statsLooper.quit();

            for (Command command : Command.values())
                commandHandler.removeMessages(command.ordinal());
            releaseLock(this);

            // Registered in command loop
            if (registeredInteractiveState) {
                unregisterReceiver(interactiveStateReceiver);
                registeredInteractiveState = false;
            }
            if (callStateListener != null) {
                TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                tm.listen(callStateListener, PhoneStateListener.LISTEN_NONE);
                callStateListener = null;
            }

            // Register in onCreate
            if (registeredUser) {
                unregisterReceiver(userReceiver);
                registeredUser = false;
            }
            if (registeredIdleState) {
                unregisterReceiver(idleStateReceiver);
                registeredIdleState = false;
            }
            if (registeredPackageChanged) {
                unregisterReceiver(packageChangedReceiver);
                registeredPackageChanged = false;
            }

            if (networkCallback != null) {
                unlistenNetworkChanges();
                networkCallback = null;
            }
            if (registeredConnectivityChanged) {
                unregisterReceiver(connectivityChangedReceiver);
                registeredConnectivityChanged = false;
            }

            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.unregisterNetworkCallback(networkMonitorCallback);

            if (phone_state) {
                TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
                tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
                phone_state = false;
            }

            try {
                if (vpn != null) {
                    stopNative(vpn);
                    stopVPN(vpn);
                    vpn = null;
                    unprepare();
                }
            } catch (Throwable ex) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex));
            }

            Log.i(TAG, "Destroy context=" + jni_context);
            synchronized (jni_lock) {
                jni_done(jni_context);
                jni_context = 0;
            }
        }

        super.onDestroy();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void unlistenNetworkChanges() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.unregisterNetworkCallback((ConnectivityManager.NetworkCallback) networkCallback);
    }

    private Notification getEnforcingNotification(int allowed, int blocked, int hosts) {
        Intent main = new Intent(this, ActivityMain.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        Intent i = new Intent(INTENT_PAUSE);
        i.setPackage(this.getPackageName());
        PendingIntent pauseIntent = PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ServiceSinkhole.this);
        int pause = Integer.parseInt(prefs.getString("pause", "10"));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "foreground");
        builder.setSmallIcon(isLockedDown(last_metered) ? R.drawable.ic_lock_outline_white_24dp : R.drawable.ic_rocket_white)
                .setContentIntent(pi)
                .setColor(getResources().getColor(R.color.colorTrackerControl))
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(R.drawable.ic_pause_white_24dp, getResources().getQuantityString(
                        R.plurals.pause, pause, pause), pauseIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            builder.setContentTitle(getString(R.string.msg_started));
        else
            builder.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.msg_started));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setPriority(NotificationCompat.PRIORITY_MIN);

        if (allowed >= 0)
            last_allowed = allowed;
        else
            allowed = last_allowed;
        if (blocked >= 0)
            last_blocked = blocked;
        else
            blocked = last_blocked;
        if (hosts >= 0)
            last_hosts = hosts;
        else
            hosts = last_hosts;

        if (allowed >= 0 || blocked >= 0 || hosts >= 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                /*if (Util.isPlayStoreInstall(this))
                    builder.setContentText(getString(R.string.msg_packages, allowed, blocked));
                else
                    builder.setContentText(getString(R.string.msg_hosts, allowed, blocked, hosts));*/
                return builder.build();
            } else {
                NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
                notification.bigText(getString(R.string.msg_started));
                /*if (Util.isPlayStoreInstall(this))
                    notification.setSummaryText(getString(R.string.msg_packages, allowed, blocked));
                else
                    notification.setSummaryText(getString(R.string.msg_hosts, allowed, blocked, hosts));*/
                return notification.build();
            }
        } else
            return builder.build();
    }

    private void updateEnforcingNotification(int allowed, int total) {
        // Update notification
        Notification notification = getEnforcingNotification(allowed, total - allowed, mapHostsBlocked.size());
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFY_ENFORCING, notification);
    }

    private Notification getWaitingNotification() {
        Intent main = new Intent(this, ActivityMain.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "foreground");
        builder.setSmallIcon(R.drawable.ic_rocket_white)
                .setContentIntent(pi)
                .setColor(getResources().getColor(R.color.colorTrackerControl))
                .setOngoing(true)
                .setAutoCancel(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            builder.setContentTitle(getString(R.string.msg_waiting));
        else
            builder.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.msg_waiting));

        return builder.build();
    }

    private void showDisabledNotification() {
        Intent main = new Intent(this, ActivityMain.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "notify");
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_revoked))
                .setContentIntent(pi)
                .setColor(getResources().getColor(R.color.colorTrackerControl))
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET);

        NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
        notification.bigText(getString(R.string.msg_revoked));

        NotificationManagerCompat.from(this).notify(NOTIFY_DISABLED, notification.build());
    }

    private void showLockdownNotification() {
        Intent intent = new Intent(Settings.ACTION_VPN_SETTINGS);
        PendingIntent pi = PendingIntent.getActivity(this, NOTIFY_LOCKDOWN, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorOff, tv, true);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "notify");
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_always_on_lockdown))
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(tv.data)
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET);

        NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
        notification.bigText(getString(R.string.msg_always_on_lockdown));

        NotificationManagerCompat.from(this).notify(NOTIFY_LOCKDOWN, notification.build());
    }

    private void removeLockdownNotification() {
        NotificationManagerCompat.from(this).cancel(NOTIFY_LOCKDOWN);
    }

    private void showAutoStartNotification() {
        Intent main = new Intent(this, ActivityMain.class);
        main.putExtra(ActivityMain.EXTRA_APPROVE, true);
        PendingIntent pi = PendingIntent.getActivity(this, NOTIFY_AUTOSTART, main, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "notify");
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_autostart))
                .setContentIntent(pi)
                .setColor(getResources().getColor(R.color.colorTrackerControl))
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET);

        NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
        notification.bigText(getString(R.string.msg_autostart));

        NotificationManagerCompat.from(this).notify(NOTIFY_AUTOSTART, notification.build());
    }

    private void showErrorNotification(String message) {
        Intent main = new Intent(this, ActivityMain.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "notify");
        builder.setSmallIcon(R.drawable.ic_error_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.msg_error, message))
                .setContentIntent(pi)
                .setColor(getResources().getColor(R.color.colorTrackerControl))
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET);

        NotificationCompat.BigTextStyle notification = new NotificationCompat.BigTextStyle(builder);
        notification.bigText(getString(R.string.msg_error, message));
        notification.setSummaryText(message);

        NotificationManagerCompat.from(this).notify(NOTIFY_ERROR, notification.build());
    }

    private void showUpdateNotification(String name, String url) {
        Intent download = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        PendingIntent pi = PendingIntent.getActivity(this, 0, download, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "notify");
        builder.setSmallIcon(R.drawable.ic_rocket_white)
                .setContentTitle(name)
                .setContentText(getString(R.string.msg_update))
                .setContentIntent(pi)
                .setColor(getResources().getColor(R.color.colorTrackerControl))
                .setOngoing(false)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_SECRET);

        NotificationManagerCompat.from(this).notify(NOTIFY_UPDATE, builder.build());
    }

    private void removeWarningNotifications() {
        NotificationManagerCompat.from(this).cancel(NOTIFY_DISABLED);
        NotificationManagerCompat.from(this).cancel(NOTIFY_AUTOSTART);
        NotificationManagerCompat.from(this).cancel(NOTIFY_ERROR);
    }

    private class Builder extends VpnService.Builder {
        private NetworkInfo networkInfo;
        private int mtu;
        private List<String> listAddress = new ArrayList<>();
        private List<String> listRoute = new ArrayList<>();
        private List<InetAddress> listDns = new ArrayList<>();
        private List<String> listDisallowed = new ArrayList<>();

        private Builder() {
            super();
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            networkInfo = cm.getActiveNetworkInfo();
        }

        @Override
        public VpnService.Builder setMtu(int mtu) {
            this.mtu = mtu;
            super.setMtu(mtu);
            return this;
        }

        @Override
        public Builder addAddress(String address, int prefixLength) {
            listAddress.add(address + "/" + prefixLength);
            super.addAddress(address, prefixLength);
            return this;
        }

        @Override
        public Builder addRoute(String address, int prefixLength) {
            listRoute.add(address + "/" + prefixLength);
            super.addRoute(address, prefixLength);
            return this;
        }

        @Override
        public Builder addRoute(InetAddress address, int prefixLength) {
            listRoute.add(address.getHostAddress() + "/" + prefixLength);
            super.addRoute(address, prefixLength);
            return this;
        }

        @Override
        public Builder addDnsServer(InetAddress address) {
            listDns.add(address);
            super.addDnsServer(address);
            return this;
        }

        /**
         * Excludes apps, such as system apps if disabled, as well as deactivated apps by user
         * @param packageName
         * @return
         * @throws PackageManager.NameNotFoundException
         */
        @Override
        public Builder addDisallowedApplication(String packageName) throws PackageManager.NameNotFoundException {
            listDisallowed.add(packageName);
            super.addDisallowedApplication(packageName);
            return this;
        }

        @Override
        public boolean equals(Object obj) {
            Builder other = (Builder) obj;

            if (other == null)
                return false;

            if (this.networkInfo == null || other.networkInfo == null ||
                    this.networkInfo.getType() != other.networkInfo.getType())
                return false;

            if (this.mtu != other.mtu)
                return false;

            if (this.listAddress.size() != other.listAddress.size())
                return false;

            if (this.listRoute.size() != other.listRoute.size())
                return false;

            if (this.listDns.size() != other.listDns.size())
                return false;

            if (this.listDisallowed.size() != other.listDisallowed.size())
                return false;

            for (String address : this.listAddress)
                if (!other.listAddress.contains(address))
                    return false;

            for (String route : this.listRoute)
                if (!other.listRoute.contains(route))
                    return false;

            for (InetAddress dns : this.listDns)
                if (!other.listDns.contains(dns))
                    return false;

            for (String pkg : this.listDisallowed)
                if (!other.listDisallowed.contains(pkg))
                    return false;

            return true;
        }
    }

    private class IPKey {
        int version;
        int protocol;
        int dport;
        int uid;

        public IPKey(int version, int protocol, int dport, int uid) {
            this.version = version;
            this.protocol = protocol;
            // Only TCP (6) and UDP (17) have port numbers
            this.dport = (protocol == 6 || protocol == 17 ? dport : 0);
            this.uid = uid;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof IPKey))
                return false;
            IPKey other = (IPKey) obj;
            return (this.version == other.version &&
                    this.protocol == other.protocol &&
                    this.dport == other.dport &&
                    this.uid == other.uid);
        }

        @Override
        public int hashCode() {
            return (version << 40) | (protocol << 32) | (dport << 16) | uid;
        }

        @Override
        public String toString() {
            return "v" + version + " p" + protocol + " port=" + dport + " uid=" + uid;
        }
    }

    private class Expiring<T> {
        private T t;
        private long expires;

        public Expiring(T t, long expires) {
            this.t = t;
            this.expires = expires;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > this.expires;
        }

        public T getOrExpired() {
            if (isExpired())
                return null;

            return t;
        }
    }

    private class IPRule {
        private IPKey key;
        private String name;
        private boolean block;
        private long expires;

        public IPRule(IPKey key, String name, boolean block, long expires) {
            this.key = key;
            this.name = name;
            this.block = block;
            this.expires = expires;
        }

        public boolean isBlocked() {
            return this.block;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > this.expires;
        }

        public void updateExpires(long expires) {
            this.expires = Math.max(this.expires, expires);
        }

        @Override
        public boolean equals(Object obj) {
            IPRule other = (IPRule) obj;
            return (this.block == other.block && this.expires == other.expires);
        }

        @Override
        public String toString() {
            return this.key + " " + this.name;
        }
    }

    public static void run(String reason, Context context) {
        Intent intent = new Intent(context, ServiceSinkhole.class);
        intent.putExtra(EXTRA_COMMAND, Command.run);
        intent.putExtra(EXTRA_REASON, reason);

        ContextCompat.startForegroundService(context, intent);
    }

    public static void start(String reason, Context context) {
        Intent intent = new Intent(context, ServiceSinkhole.class);
        intent.putExtra(EXTRA_COMMAND, Command.start);
        intent.putExtra(EXTRA_REASON, reason);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void reload(String reason, Context context, boolean interactive) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean("enabled", false)) {
            Intent intent = new Intent(context, ServiceSinkhole.class);
            intent.putExtra(EXTRA_COMMAND, Command.reload);
            intent.putExtra(EXTRA_REASON, reason);
            intent.putExtra(EXTRA_INTERACTIVE, interactive);
            ContextCompat.startForegroundService(context, intent);
        }
    }

    public static void stop(String reason, Context context, boolean vpnonly) {
        Intent intent = new Intent(context, ServiceSinkhole.class);
        intent.putExtra(EXTRA_COMMAND, Command.stop);
        intent.putExtra(EXTRA_REASON, reason);
        intent.putExtra(EXTRA_TEMPORARY, vpnonly);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void reloadStats(String reason, Context context) {
        Intent intent = new Intent(context, ServiceSinkhole.class);
        intent.putExtra(EXTRA_COMMAND, Command.stats);
        intent.putExtra(EXTRA_REASON, reason);
        ContextCompat.startForegroundService(context, intent);
    }
}
