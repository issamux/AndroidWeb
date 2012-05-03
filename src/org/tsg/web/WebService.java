package org.tsg.web;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Log;

public class WebService extends Service {

    private static boolean LONG_CACHE = false;

    //
    private static boolean LOGGING = false;
    private static final String LOG_TAG = "WebService";

    //
    public static final int METHOD_GET = 0;
    public static final int METHOD_POST = 1;
    public static final int METHOD_PUT = 2;
    public static final int METHOD_DELETE = 3;

    public static final int CONTENT_AUTO = 0;
    public static final int CONTENT_RAW = 1;
    public static final int CONTENT_STRING = 2;

    public static final int STATUS_CREATED = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_FINISHED = 2;
    public static final int STATUS_ERROR = 3;

    public static final int TIME_SECOND = 0;
    public static final int TIME_MINUTE = 1;
    public static final int TIME_HOUR = 2;
    public static final int TIME_DAY = 3;
    public static final int TIME_MONTH = 4;
    public static final int TIME_YEAR = 5;

    // keys for Bundle resultData sent to receiver
    public static final String REQUEST_KEY = "requestKey";

    public static final String RESPONSE_EXCEPTION = "exception";
    public static final String RESPONSE_CODE = "responseCode";
    public static final String RESPONSE_MESSAGE = "responseMessage";

    public static final String DEVELOPER_EXTRAS = "developerExtras";

    //
    static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";
    static final String ENCODING_GZIP = "gzip";

    //
    private static int POOL_SIZE = 3;

    // managed during service life cycle
    private WebContentProvider.Database mDatabase;
    private ExecutorService mPool;

    // private static Map<String, Intent> mIntents;
    private static Map<String, WebServiceResultReceiver> mResultReceivers;

    /**
     * Debug logging for org.tsg.web classes.
     * 
     * @param objects
     */
    static void log(int i, Object... objects) {
        if (LOGGING) {
            String msg = "";
            for (Object object : objects) {
                msg += String.valueOf(object) + " ";
            }

            switch (i) {
                case Log.DEBUG:
                    Log.d(LOG_TAG, msg);
                    break;
                case Log.ERROR:
                    Log.e(LOG_TAG, msg);
                    break;
                case Log.INFO:
                    Log.i(LOG_TAG, msg);
                    break;
                case Log.WARN:
                    Log.w(LOG_TAG, msg);
                    break;
            }
        }
    }

    /**
     * Enable logging for web package.
     */
    public static void enableLogging() {
        LOGGING = true;
    }

    /**
     * Set the number of executor threads available for handling queued
     * requests.
     * 
     * @param size
     */
    public static void setPoolSize(int size) {
        POOL_SIZE = size;
    }

    /**
     * Useful if requests are already cached and working off-line. Sets all
     * request cache lengths to 999 years, forcing a pull from cache db if
     * previously fetched.
     */
    public static void enableLongCache() {
        LONG_CACHE = true;
    }

    /**
     * Set max size of data in cache db, auto pruning oldest requests first.
     * Default is 5000 kilobytes. TODO implement
     * 
     * @param kilobytes
     */
    public static void setMaxCacheSize(int kilobytes) {
        WebContentProvider.MAX_CACHE_SIZE = kilobytes;
    }

    /**
     * Runnable that executes network request when service handles an intent.
     */
    protected final class WebServiceHandler implements Runnable {

        private Intent mIntent;
        private int mStartId;

        public WebServiceHandler(Intent intent, int startId) {
            mIntent = intent;
            mStartId = startId;
        }

        @Override
        public void run() {
            Bundle data = mIntent.getExtras();

            ResultReceiver receiver = data.getParcelable("receiver");
            WebRequest request = data.getParcelable("request");

            WebClient client = new WebClient(request);

            Bundle bundle = new Bundle();
            bundle.putBundle(DEVELOPER_EXTRAS, request.mDeveloperExtras);

            String uuid = mIntent.getStringExtra("uuid");
            String cacheKey = mIntent.getStringExtra("cacheKey");

            if (LONG_CACHE) {
                request.mCacheTimeValue = 999;
                request.mCacheTimeType = TIME_YEAR;
            }

            try {
                if (request.mFakeData != null) {
                    mDatabase.put(cacheKey, uuid, client.mContentType, request.mFakeData.getBytes(), null);
                } else if (mDatabase.contains(cacheKey, request.mCacheTimeValue, request.mCacheTimeType)) {
                    bundle.putBoolean("fromCache", true);
                } else {
                    // only send STATUS_RUNNING if making an actual service call
                    mIntent.putExtra("status", STATUS_RUNNING);
                    bundle.putString(REQUEST_KEY, cacheKey);
                    receiver.send(STATUS_RUNNING, bundle);
                    bundle = new Bundle();
                    bundle.putBundle(DEVELOPER_EXTRAS, request.mDeveloperExtras);
                    //
                    client.call();
                    mDatabase.put(cacheKey, uuid, client.mContentType, client.mResponseBytes, client.mResponseContentType);
                    bundle.putInt(RESPONSE_CODE, client.mResponseCode);
                    bundle.putString(RESPONSE_MESSAGE, client.mResponseMessage);
                }

                bundle.putString(REQUEST_KEY, cacheKey);
                receiver.send(STATUS_FINISHED, bundle);
                mIntent.putExtra("status", STATUS_FINISHED);
            } catch (Exception e) {
                bundle.putSerializable("exception", e);
                receiver.send(STATUS_ERROR, bundle);
                mIntent.putExtra("status", STATUS_ERROR);
            }

            stopSelf(mStartId);
        }

    }

    /**
     * A parcelable object that stores the Receiver interface for service
     * callbacks.
     */
    private static class WebServiceResultReceiver extends ResultReceiver {

        private static final String KEY_RESULT_CODE = "resultCode";
        private static final String KEY_RESULT_DATA = "resultData";

        private List<WebReceiver> mReceivers = new ArrayList<WebReceiver>();
        private Bundle mLastResult;
        private String mRequestKey;
        private boolean mIsPending = false;

        public WebServiceResultReceiver(Handler handler, String requestKey) {
            super(handler);
            mRequestKey = requestKey;

            mLastResult = new Bundle();
            mLastResult.putInt(KEY_RESULT_CODE, STATUS_CREATED);
            mLastResult.putBundle(KEY_RESULT_DATA, Bundle.EMPTY);
        }

        public boolean isPending() {
            return mIsPending;
        }

        public String getRequestKey() {
            return mRequestKey;
        }

        public synchronized List<WebReceiver> getReceivers() {
            return mReceivers;
        }

        /**
         * Add receiver if not already contained and issue any pending/previous
         * results
         * 
         * @param receiver
         */
        public void addReceiver(WebReceiver receiver) {
            synchronized (mReceivers) {
                if (!mReceivers.contains(receiver))
                    mReceivers.add(receiver);
            }

            if (mLastResult != null) {
                // TODO are there race conditions here?
                int resultCode = mLastResult.getInt(KEY_RESULT_CODE);
                Bundle resultData = mLastResult.getBundle(KEY_RESULT_DATA);
                onReceiveResult(resultCode, resultData);
            }
        }

        /**
		 * 
		 */
        @Override
        protected synchronized void onReceiveResult(int resultCode, Bundle resultData) {
            mLastResult.putInt(KEY_RESULT_CODE, resultCode);
            mLastResult.putBundle(KEY_RESULT_DATA, resultData);

            synchronized (mReceivers) {
                mIsPending = (mReceivers.size() == 0);

                if (!mIsPending) {
                    for (WebReceiver receiver : mReceivers) {
                        receiver.onReceiveResult(resultCode, resultData);
                    }
                }
            }
        }
    }

    /**
     * Remove all receivers from all service intents.
     */
    public static void clearReceivers() {
        if (mResultReceivers == null)
            return;

        synchronized (mResultReceivers) {
            for (Entry<String, WebServiceResultReceiver> entry : mResultReceivers.entrySet()) {
                WebServiceResultReceiver resultReceiver = entry.getValue();
                resultReceiver.getReceivers().clear();
            }
        }

    }

    /**
     * Remove this receiver from any service intents it's currently attached to.
     * 
     * @param receiver
     */
    public static void removeReceiver(WebReceiver receiver) {
        if (mResultReceivers == null)
            return;

        synchronized (mResultReceivers) {
            for (Entry<String, WebServiceResultReceiver> entry : mResultReceivers.entrySet()) {
                WebServiceResultReceiver resultReceiver = entry.getValue();
                if (resultReceiver.getReceivers().contains(receiver)) {
                    resultReceiver.getReceivers().remove(receiver);
                }
            }
        }
    }

    /**
     * Convenience method when Service.Receiver is implemented by Context.
     * 
     * @param context
     * @param request
     * @return
     */
    public static String helper(Context context, WebRequest request) {
        return helper(context, null, (WebReceiver) context, request);
    }

    public static String helper(Context context, WebReceiver receiver, WebRequest request) {
        return helper(context, null, receiver, request);
    }

    /**
     * When working with threads, pass in a Handler tied to GUI thread for
     * modifying views in Receiver.
     * 
     * @param context
     * @param handler
     * @param receiver
     * @param request
     * @return
     */
    public static String helper(Context context, Handler handler, WebReceiver receiver, WebRequest request) {
        String uuid = UUID.randomUUID().toString();
        String cacheKey = request.getKey();

        // Check if cache is still valid to avoid queueing this request behind
        // valid web requests
        if (WebContentProvider.Database.getInstance(context.getApplicationContext()).contains(cacheKey, request.mCacheTimeValue, request.mCacheTimeType)) {
            Bundle bundle = new Bundle();
            bundle.putString(REQUEST_KEY, cacheKey);
            bundle.putBundle(DEVELOPER_EXTRAS, request.mDeveloperExtras);
            bundle.putBoolean("fromCache", true);
            receiver.onReceiveResult(STATUS_FINISHED, bundle);
            return cacheKey;
        }

        // Check if Service is already pending
        if (mResultReceivers == null) {
            // this is always getting nullified when service finishes all
            // requests
            mResultReceivers = new HashMap<String, WebServiceResultReceiver>();
        }

        synchronized (mResultReceivers) {
            for (Entry<String, WebServiceResultReceiver> entry : mResultReceivers.entrySet()) {

                WebServiceResultReceiver resultReceiver = entry.getValue();
                String cacheKeyExtra = resultReceiver.getRequestKey();

                if (cacheKey.equals(cacheKeyExtra)) {
                    // TODO are there race conditions here?
                    // TODO should probably already be missing from by now, is
                    // check necessary?
                    int statusExtra = resultReceiver.mLastResult.getInt(WebServiceResultReceiver.KEY_RESULT_CODE);
                    if (statusExtra == STATUS_CREATED || statusExtra == STATUS_RUNNING || resultReceiver.isPending()) {
                        resultReceiver.addReceiver(receiver);
                        return cacheKey;
                    }
                }
            }
        }

        if (handler == null)
            handler = new Handler();
        WebServiceResultReceiver resultReceiver = new WebServiceResultReceiver(handler, cacheKey);
        resultReceiver.addReceiver(receiver);

        Intent service = new Intent(Intent.ACTION_SYNC, null, context.getApplicationContext(), WebService.class);
        service.putExtra("receiver", resultReceiver);
        service.putExtra("request", request);
        service.putExtra("uuid", uuid);
        service.putExtra("cacheKey", cacheKey);

        synchronized (mResultReceivers) {
            mResultReceivers.put(cacheKey, resultReceiver);
        }

        context.getApplicationContext().startService(service);

        return cacheKey;
    }

    public static byte[] getResponseBytes(Context context, String responseKey) {
        byte[] bytes = null;
        Uri uri = WebContentProvider.getDefaultAuthority(context).buildUpon().appendPath(responseKey).build();

        Cursor c = context.getContentResolver().query(uri, null, null, null, null);
        if (c.moveToFirst()) {
            bytes = c.getBlob(0);

            if (bytes == null) {
                InputStream inputStream = null;
                try {
                    inputStream = context.getContentResolver().openInputStream(uri);
                    ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
                    int bufferSize = 1024;
                    byte[] buffer = new byte[bufferSize];
                    int len = 0;
                    while ((len = inputStream.read(buffer)) != -1) {
                        byteBuffer.write(buffer, 0, len);
                    }
                    bytes = byteBuffer.toByteArray();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        c.close();
        return bytes;
    }

    public static String getResponseString(Context context, String responseKey) {
        return new String(getResponseBytes(context, responseKey));
    }

    /* Service Implementation */

    @Override
    public void onCreate() {
        mPool = Executors.newFixedThreadPool(POOL_SIZE);
        // mPool = Executors.newSingleThreadExecutor();
        // mPool = Executors.newFixedThreadPool(POOL_SIZE, new
        // ServiceThreadFactory());

        if (mResultReceivers == null) {
            mResultReceivers = new HashMap<String, WebServiceResultReceiver>();
        }
        mDatabase = WebContentProvider.Database.getInstance(getApplicationContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mPool.execute(new WebServiceHandler(intent, startId));
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {

        synchronized (mResultReceivers) {
            mResultReceivers.clear();
            mResultReceivers = null;
        }

        mPool.shutdown();
        try {
            if (!mPool.awaitTermination(5, TimeUnit.SECONDS)) {
                mPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            mPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /*
     * private static class WebServiceThreadFactory implements ThreadFactory {
     * 
     * @Override public Thread newThread(Runnable r) { Thread thread = new
     * Thread(r); thread.setPriority(Thread.MIN_PRIORITY); return thread; }
     * 
     * }
     */

}