
package org.ohmage.service;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.text.TextUtils;

import com.commonsware.cwac.wakeful.WakefulIntentService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.ohmage.AccountHelper;
import org.ohmage.ConfigHelper;
import org.ohmage.NotificationHelper;
import org.ohmage.OhmageApi;
import org.ohmage.OhmageApi.UploadResponse;
import org.ohmage.PreferenceStore;
import org.ohmage.logprobe.Analytics;
import org.ohmage.logprobe.Log;
import org.ohmage.logprobe.LogProbe.Status;
import org.ohmage.probemanager.DbContract.BaseProbeColumns;
import org.ohmage.probemanager.DbContract.Probe;
import org.ohmage.probemanager.DbContract.Probes;
import org.ohmage.probemanager.DbContract.Responses;

import java.util.ArrayList;

public class ProbeUploadService extends WakefulIntentService {

    /** Extra to tell the upload service if it is running in the background **/
    public static final String EXTRA_BACKGROUND = "is_background";

    /** Extra to tell the upload service to only upload one probe **/
    public static final String EXTRA_OBSERVER_ID = "extra_observer_id";

    /**
     * Extra to tell the upload service to only upload the probe with this
     * version. Ignored if {@link ProbeUploadService#EXTRA_OBSERVER_ID} is not
     * specified.
     */
    public static final String EXTRA_OBSERVER_VERSION = "extra_observer_version";

    /** Uploaded in batches of 1 mb */
    private static final int BATCH_SIZE = 1024 * 1024;

    private static final String TAG = "ProbeUploadService";

    public static final String PROBE_UPLOAD_STARTED = "org.ohmage.PROBE_UPLOAD_STARTED";
    public static final String PROBE_UPLOAD_FINISHED = "org.ohmage.PROBE_UPLOAD_FINISHED";
    public static final String PROBE_UPLOAD_ERROR = "org.ohmage.PROBE_UPLOAD_ERROR";

    public static final String RESPONSE_UPLOAD_STARTED = "org.ohmage.RESPONSE_UPLOAD_STARTED";
    public static final String RESPONSE_UPLOAD_FINISHED = "org.ohmage.RESPONSE_UPLOAD_FINISHED";
    public static final String RESPONSE_UPLOAD_ERROR = "org.ohmage.RESPONSE_UPLOAD_ERROR";

    public static final String PROBE_UPLOAD_SERVICE_FINISHED = "org.ohmage.PROBE_UPLOAD_SERVICE_FINISHED";

    public static final String EXTRA_PROBE_ERROR = "extra_probe_error";

    private OhmageApi mApi;

    private boolean isBackground;

    /**
     * Set to true if there was an error uploading data
     */
    private boolean mError = false;

    private AccountHelper mAccount;
    private PreferenceStore mPrefs;

    private String mObserverId = null;

    private String mObserverVersion = null;

    public ProbeUploadService() {
        super(TAG);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Analytics.service(this, Status.ON);
        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Analytics.service(this, Status.OFF);
    }

    @Override
    protected void doWakefulWork(Intent intent) {
        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);

        mAccount = new AccountHelper(ProbeUploadService.this);
        mPrefs = new PreferenceStore(this);

        if (mApi == null)
            setOhmageApi(new OhmageApi(this));

        isBackground = intent.getBooleanExtra(EXTRA_BACKGROUND, false);

        mObserverId = intent.getStringExtra(EXTRA_OBSERVER_ID);
        if (mObserverId != null)
            mObserverVersion = intent.getStringExtra(EXTRA_OBSERVER_VERSION);

        Log.v(TAG, "upload probes");
        ProbesUploader probesUploader = new ProbesUploader();
        probesUploader.upload();
        Log.v(TAG, "upload responses");
        ResponsesUploader responsesUploader = new ResponsesUploader();
        responsesUploader.upload();

        // If there were no internal errors, we can say it was successful
        if (!probesUploader.hadError() && !responsesUploader.hadError())
            mPrefs.edit().putLastProbeUploadTimestamp(System.currentTimeMillis()).commit();

        sendBroadcast(new Intent(ProbeUploadService.PROBE_UPLOAD_SERVICE_FINISHED));
    }

    public void setOhmageApi(OhmageApi api) {
        mApi = api;
    }

    /**
     * Abstraction to upload object from the probes db. Uploads data in chunks
     * based on the {@link #getName(Cursor)} and {@link #getVersion(Cursor)}
     * values.
     * 
     * @author cketcham
     */
    public abstract class Uploader {

        protected JsonParser mParser;

        public Uploader() {
            mParser = new JsonParser();
        }

        protected abstract Uri getContentURI();

        protected abstract UploadResponse uploadCall(String serverUrl, String username,
                String password, String client, String name, String version, JsonArray data);

        protected abstract void uploadStarted();

        protected abstract void uploadFinished();

        protected abstract void uploadError(String string);

        /**
         * Creates json representation of probe
         * 
         * @param c
         * @return the probe json
         */
        public abstract JsonElement createProbe(Cursor c);

        protected abstract int getVersionIndex();

        protected abstract int getNameIndex();

        protected abstract String getVersionColumn();

        protected abstract String getNameColumn();

        protected abstract String[] getProjection();

        public void upload() {

            uploadStarted();

            ArrayList<Probe> observers = queryObservers();

            for (Probe o : observers) {
                ArrayList<Long> ids = null;

                while (ids == null || !ids.isEmpty()) {

                    JsonArray probes = new JsonArray();

                    ids = queryProbes(probes, o, BATCH_SIZE);
                    if (!ids.isEmpty()) {

                        Log.d(TAG, "uploading " + ids.size() + " points for " + o.observer_id
                                + " v" + o.observer_version);
                        if (!upload(probes, o.observer_id, o.observer_version)) {
                            return;
                        }

                        StringBuilder deleteString = new StringBuilder();

                        // Deleting this batch of points. We can only delete
                        // with a maximum expression tree depth of 1000
                        for (int batch = 0; batch < ids.size(); batch++) {
                            if (deleteString.length() != 0)
                                deleteString.append(" OR ");
                            deleteString.append(BaseColumns._ID + "=" + ids.get(batch));

                            // If we have 1000 Expressions or we are at the last
                            // point, delete them
                            if ((batch != 0 && batch % (1000 - 2) == 0) || batch == ids.size() - 1) {
                                getContentResolver().delete(getContentURI(),
                                        deleteString.toString(), null);
                                deleteString = new StringBuilder();
                            }
                        }
                    }

                }

            }

            uploadFinished();
        }

        /**
         * Query for a list of observers which have data
         * 
         * @return
         */
        private ArrayList<Probe> queryObservers() {
            String select = BaseProbeColumns.USERNAME + "=?";
            if (mObserverId != null)
                select += " AND " + getNameColumn() + "='" + mObserverId + "'";

            if (mObserverVersion != null)
                select += " AND " + getVersionColumn() + "='" + mObserverVersion + "'";

            Cursor observersCursor = getContentResolver().query(getContentURI(), new String[] {
                    "distinct " + getNameColumn(), getVersionColumn()
            }, select, new String[] {
                mAccount.getUsername()
            }, null);

            ArrayList<Probe> observers = new ArrayList<Probe>();

            while (observersCursor.moveToNext()) {
                observers
                        .add(new Probe(observersCursor.getString(0), observersCursor.getString(1)));
            }
            observersCursor.close();
            return observers;
        }

        /**
         * Queries the DB for probes which are up to a given size in length.
         * Adds the probes to the probes JsonArray.
         * 
         * @param probes
         * @param o
         * @param size
         * @return a list of ids of probes which were added so they can easily
         *         be deleted
         */
        private ArrayList<Long> queryProbes(JsonArray probes, Probe o, int size) {

            ArrayList<Long> ids = new ArrayList<Long>();

            Cursor c = getContentResolver().query(
                    getContentURI(),
                    getProjection(),
                    BaseProbeColumns.USERNAME + "=? AND " + getNameColumn() + "=? AND "
                            + getVersionColumn() + "=?", new String[] {
                            mAccount.getUsername(), o.observer_id, o.observer_version
                    }, null);

            int count = c.getCount();

            for (int i = 0; i < count; i++) {

                if (!c.moveToNext()) {
                    Log.e(TAG, "There was an error querying the probe database");
                    break;
                }

                JsonElement point = createProbe(c);

                // Can we add this point without going over the size limit
                if (size - point.toString().length() < 0) {
                    // If this point is too big and we have points to send then
                    // finish, otherwise this point is skipped
                    if (ids.size() > 0) {
                        break;
                    }
                } else {
                    probes.add(point);
                    size -= point.toString().length();
                    ids.add(c.getLong(0));
                }
            }

            c.close();
            return ids;
        }

        /**
         * Uploads probes to the server
         * 
         * @param probes the probe json
         * @param c the cursor object
         * @return false only if there was an error which indicates we shouldn't
         *         continue uploading
         */
        private boolean upload(JsonArray probes, String observerId, String observerVersion) {

            String username = mAccount.getUsername();
            String hashedPassword = mAccount.getAuthToken();

            // If there are no probes to upload just return successful
            if (probes.size() > 0) {

                UploadResponse response = uploadCall(ConfigHelper.serverUrl(), username,
                        hashedPassword, OhmageApi.CLIENT_NAME, observerId, observerVersion, probes);
                response.handleError(ProbeUploadService.this);

                if (response.getResult().equals(OhmageApi.Result.FAILURE)) {
                    if (response.hasAuthError())
                        return false;
                    mError = true;
                    uploadError(observerId + response.getErrorCodes().toString());
                    Log.w(TAG,
                            "Some Probes failed to upload for " + observerId + " "
                                    + response.getErrorCodes());
                } else if (!response.getResult().equals(OhmageApi.Result.SUCCESS)) {
                    mError = true;
                    uploadError(null);
                    return false;
                }
            }
            return true;
        }

        public boolean hadError() {
            return mError;
        }
    }

    private interface ProbeQuery {
        static final String[] PROJECTION = new String[] {
                Probes._ID, Probes.OBSERVER_ID, Probes.OBSERVER_VERSION, Probes.STREAM_ID,
                Probes.STREAM_VERSION, Probes.PROBE_METADATA, Probes.PROBE_DATA
        };

        static final int OBSERVER_ID = 1;
        static final int OBSERVER_VERSION = 2;
        static final int STREAM_ID = 3;
        static final int STREAM_VERSION = 4;
        static final int PROBE_METADATA = 5;
        static final int PROBE_DATA = 6;
    }

    public class ProbesUploader extends Uploader {

        @Override
        protected String[] getProjection() {
            return ProbeQuery.PROJECTION;
        }

        @Override
        protected int getNameIndex() {
            return ProbeQuery.OBSERVER_ID;
        }

        @Override
        protected int getVersionIndex() {
            return ProbeQuery.OBSERVER_VERSION;
        }

        @Override
        public JsonElement createProbe(Cursor c) {
            JsonObject probe = new JsonObject();
            probe.addProperty("stream_id", c.getString(ProbeQuery.STREAM_ID));
            probe.addProperty("stream_version", c.getInt(ProbeQuery.STREAM_VERSION));
            String data = c.getString(ProbeQuery.PROBE_DATA);
            if (!TextUtils.isEmpty(data)) {
                probe.add("data", mParser.parse(data));
            }
            String metadata = c.getString(ProbeQuery.PROBE_METADATA);
            if (!TextUtils.isEmpty(metadata)) {
                probe.add("metadata", mParser.parse(metadata));
            }
            return probe;
        }

        @Override
        protected void uploadStarted() {
            sendBroadcast(new Intent(ProbeUploadService.PROBE_UPLOAD_STARTED));
        }

        @Override
        protected void uploadFinished() {
            sendBroadcast(new Intent(ProbeUploadService.PROBE_UPLOAD_FINISHED));
        }

        @Override
        protected void uploadError(String error) {
            if (isBackground) {
                if (error != null)
                    NotificationHelper.showProbeUploadErrorNotification(ProbeUploadService.this,
                            error);
            } else {
                Intent broadcast = new Intent(ProbeUploadService.PROBE_UPLOAD_ERROR);
                if (error != null)
                    broadcast.putExtra(EXTRA_PROBE_ERROR, error);
                sendBroadcast(broadcast);
            }
        }

        @Override
        protected Uri getContentURI() {
            return Probes.CONTENT_URI;
        }

        @Override
        protected UploadResponse uploadCall(String serverUrl, String username, String password,
                String client, String observerId, String observerVersion, JsonArray data) {
            return mApi.observerUpload(ConfigHelper.serverUrl(), username, password,
                    OhmageApi.CLIENT_NAME, observerId, observerVersion, data.toString());
        }

        @Override
        protected String getVersionColumn() {
            return Probes.OBSERVER_VERSION;
        }

        @Override
        protected String getNameColumn() {
            return Probes.OBSERVER_ID;
        }
    }

    private interface ResponseQuery {
        static final String[] PROJECTION = new String[] {
                Responses._ID, Responses.CAMPAIGN_URN, Responses.CAMPAIGN_CREATED,
                Responses.RESPONSE_DATA
        };

        static final int CAMPAIGN_URN = 1;
        static final int CAMPAIGN_CREATED = 2;
        static final int RESPONSE_DATA = 3;
    }

    public class ResponsesUploader extends Uploader {

        @Override
        protected String[] getProjection() {
            return ResponseQuery.PROJECTION;
        }

        @Override
        protected int getNameIndex() {
            return ResponseQuery.CAMPAIGN_URN;
        }

        @Override
        protected int getVersionIndex() {
            return ResponseQuery.CAMPAIGN_CREATED;
        }

        @Override
        public JsonElement createProbe(Cursor c) {
            String data = c.getString(ResponseQuery.RESPONSE_DATA);
            return mParser.parse(data);
        }

        @Override
        protected void uploadStarted() {
            sendBroadcast(new Intent(ProbeUploadService.RESPONSE_UPLOAD_STARTED));
        }

        @Override
        protected void uploadFinished() {
            sendBroadcast(new Intent(ProbeUploadService.RESPONSE_UPLOAD_FINISHED));
        }

        @Override
        protected void uploadError(String error) {
            if (isBackground) {
                if (error != null)
                    NotificationHelper.showResponseUploadErrorNotification(ProbeUploadService.this,
                            error);
            } else {
                Intent broadcast = new Intent(ProbeUploadService.RESPONSE_UPLOAD_ERROR);
                if (error != null)
                    broadcast.putExtra(EXTRA_PROBE_ERROR, error);
                sendBroadcast(broadcast);
            }
        }

        @Override
        protected Uri getContentURI() {
            return Responses.CONTENT_URI;
        }

        @Override
        protected UploadResponse uploadCall(String serverUrl, String username, String password,
                String client, String campaignUrn, String campaignCreated, JsonArray data) {
            return mApi.surveyUpload(ConfigHelper.serverUrl(), username, password,
                    OhmageApi.CLIENT_NAME, campaignUrn, campaignCreated, data.toString());
        }

        @Override
        protected String getVersionColumn() {
            return Responses.CAMPAIGN_CREATED;
        }

        @Override
        protected String getNameColumn() {
            return Responses.CAMPAIGN_URN;
        }
    }
}
