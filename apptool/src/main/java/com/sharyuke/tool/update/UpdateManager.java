package com.sharyuke.tool.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;

import com.sharyuke.tool.R;
import com.sharyuke.tool.util.FileHelper;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.otto.Bus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import retrofit.RetrofitError;
import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;
import timber.log.Timber;

public class UpdateManager {
    private static final String UPDATE_STUFF_SAVE_NAME = ".apk";

    private static final String RES_404 = "404";

    public static final String APP = "app";

    private static final int RESET = 0x01;

    private static UpdateManager mUpdateManager;

    protected CompositeSubscription subscription = new CompositeSubscription();

    private Observable<? extends ResUpdateModel> updateApi;

    private String downloadUrl;

    private int versionCode;

    private Activity activity;

    private ProgressDialog updateProgressDialog;

    private OkHttpClient client;

    private int dialogTheme;

    private Bus bus;

    private List<OnUpdateStatus> onUpdateStatus = new ArrayList<>();
    private List<OnUpdateProgress> onUpdateProgress = new ArrayList<>();

    private String appName;

    private File files;

    private String downloadAppName;

    private Handler handler;

    public static UpdateManager getInstance() {
        synchronized (UpdateManager.class) {
            if (mUpdateManager == null) {
                mUpdateManager = new UpdateManager();
            }
        }
        return mUpdateManager;
    }

    private UpdateManager() {
        client = new OkHttpClient();
        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                switch (msg.what) {
                    case RESET:
                        update();
                        break;
                }
            }
        };
    }

    private Status status = Status.NORMAL;

    public enum Status {
        NORMAL,
        CHECKING,
        DOWNLOADING,
    }

    public Bus getBus() {
        return bus;
    }

    public Status getStatus() {
        return status;
    }

    public UpdateManager initUpdateManager(Observable<? extends ResUpdateModel> updateApi, String downloadUrl, int versionCode) {
        initUpdateManager(updateApi, downloadUrl, versionCode, null, APP);
        return this;
    }

    public UpdateManager initUpdateManager(Observable<? extends ResUpdateModel> updateApi, String downloadUrl, int versionCode, Bus bus) {
        initUpdateManager(updateApi, downloadUrl, versionCode, bus, APP);
        return this;
    }

    public UpdateManager initUpdateManager(Observable<? extends ResUpdateModel> updateApi, String downloadUrl, int versionCode, String appName) {
        initUpdateManager(updateApi, downloadUrl, versionCode, null, appName);
        return this;
    }

    public UpdateManager initUpdateManager(Observable<? extends ResUpdateModel> updateApi, String downloadUrl, int versionCode, Bus bus, String appName) {
        setUpdateApi(updateApi).setDownloadUrl(downloadUrl).setVersionCode(versionCode).setAppName(appName);
        return this;
    }

    public UpdateManager setDialogTheme(int dialogTheme) {
        this.dialogTheme = dialogTheme;
        return this;
    }

    public UpdateManager setBus(Bus bus) {
        this.bus = bus;
        return this;
    }

    public UpdateManager setUpdateApi(Observable<? extends ResUpdateModel> updateApi) {
        this.updateApi = updateApi;
        return this;
    }

    public UpdateManager setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
        return this;
    }

    public UpdateManager setVersionCode(int versionCode) {
        this.versionCode = versionCode;
        return this;
    }

    public String getAppName() {
        return appName;
    }

    public UpdateManager setAppName(String appName) {
        this.appName = appName;
        return this;
    }

    public void deleteCacheFiles() {
        int tipsRes = 0;
        switch (status) {
            case NORMAL:
                tipsRes = FileHelper.deleteFile(files) ? R.string.toast_delete_something : R.string.toast_delete_nothing;
                break;
            case CHECKING:
                tipsRes = FileHelper.deleteFile(files) ? R.string.toast_delete_something : R.string.toast_delete_nothing;
                break;
            case DOWNLOADING:
                tipsRes = R.string.toast_delete_downloading;
                break;
        }
        ToastHelper.get(activity).showShort(tipsRes);
    }

    private void initProgressDialog() {
        if (dialogTheme != 0) {
            updateProgressDialog = new ProgressDialog(activity, dialogTheme);
        } else {
            updateProgressDialog = new ProgressDialog(activity);
        }
        updateProgressDialog.setMessage(activity.getText(R.string.dialog_downloading_msg));
        updateProgressDialog.setIndeterminate(false);
        updateProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        updateProgressDialog.setMax(100);
        updateProgressDialog.setProgress(0);
        updateProgressDialog.show();
        updateStatus(Status.DOWNLOADING);
    }

    private void updateStatus(Status status) {
        this.status = status;
        for (OnUpdateStatus updateStatus : onUpdateStatus) {
            updateStatus(updateStatus, status);
        }
    }

    private void updateStatus(OnUpdateStatus onUpdateStatus, Status status) {
        if (onUpdateStatus != null) {
            onUpdateStatus.onStatusChanged(status);
        }
    }

    public void checkUpdate(Activity activity) {
        checkUpdate(activity, downloadUrl);
    }

    public void checkUpdate(Activity activity, String downloadUrl) {
        checkUpdate(activity, false, downloadUrl);
    }

    public void checkUpdate(Activity activity, boolean isSaliently) {
        checkUpdate(activity, isSaliently, downloadUrl);
    }

    public void checkUpdate(Activity activity, boolean isSaliently, String downloadUrl) {
        this.downloadUrl = downloadUrl;
        this.activity = activity;
        switch (status) {
            case NORMAL:
                updateStatus(Status.CHECKING);
                subscription.add(updateApi
                        .doOnError(throwable1 -> updateStatus(Status.NORMAL))
                        .doOnError((throwable2) -> handleError(throwable2, isSaliently))
                        .onErrorResumeNext(Observable.empty())
                        .subscribe((updateResModel) -> versionInfo(updateResModel, isSaliently)));
                break;
            case CHECKING:
                updateStatus(Status.CHECKING);
                break;
            case DOWNLOADING:
                initProgressDialog();
                break;
        }
    }

    private void handleError(Throwable throwable, boolean isSaliently) {
        if (isSaliently) return;
        if (throwable instanceof RetrofitError) {
            RetrofitError cause = (RetrofitError) throwable;
            switch (cause.getKind()) {
                case NETWORK:
                    ToastHelper.get(activity).showShort(activity.getResources().getString(R.string.toast_check_network_error));
                    break;
                case CONVERSION:
                    break;
                case HTTP:
                    break;
                case UNEXPECTED:
                    break;
            }
        }
    }

    private void sendProgress(DownLoadProgress downLoadProgress) {
        if (bus != null) {
            bus.post(downLoadProgress);
        }
    }

    private void updateDownloadProgress(DownLoadProgress progress) {
        for (OnUpdateProgress onUpdate : onUpdateProgress) {
            updateDownloadProgress(onUpdate, progress);
        }
    }

    private void updateDownloadProgress(OnUpdateProgress onUpdate, DownLoadProgress progress) {
        if (onUpdate != null) {
            onUpdate.onProgressChanged(progress);
        }
    }

    private void download(Activity activity, String url, String versionName) {
        this.activity = activity;
        switch (status) {
            case NORMAL:
                break;
            case CHECKING:
                return;
            case DOWNLOADING:
                initProgressDialog();
                return;
        }
        initFileName(versionName);
        initProgressDialog();
        subscription.add(get(url)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(downLoadProgress -> {
                    updateProgressDialog.setMax((int) downLoadProgress.getTotalLength());
                    updateProgressDialog.setProgress((int) downLoadProgress.getProgress());
                    sendProgress(downLoadProgress);
                    updateDownloadProgress(downLoadProgress);
                })
                .doOnError(throwable -> {
                    updateProgressDialog.dismiss();
                    String tips = RES_404.equals(throwable.getMessage()) ? activity.getResources().getString(R.string.toast_file_not_exist) : activity.getResources().getString(R.string.toast_download_network_error);
                    ToastHelper.get(activity).showShort(tips);
                    Timber.e(throwable, "---->>");
                    updateStatus(Status.NORMAL);
                })
                .onErrorResumeNext(Observable.empty())
                .subscribe(downLoadProgress -> {
                    if (downLoadProgress.isComplete()) {
                        update();
                    }
                }));
    }

    private void initFileName(String versionName) {
        appName = TextUtils.isEmpty(appName) ? APP : appName;
        versionName = TextUtils.isEmpty(versionName) ? "debug" : versionName;
        String dir;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            dir = Environment.getExternalStorageDirectory() + "/" + appName + "/apks/";
        } else {
            dir = Environment.getDataDirectory() + "/" + appName + "/apks/";
        }
        files = new File(dir);
        if (!files.exists() && files.mkdirs()) {
            Timber.d(files.getName() + " has been created");
        }
        downloadAppName = dir + appName + "-" + versionName + UPDATE_STUFF_SAVE_NAME;
    }

    private void versionInfo(ResUpdateModel resCheckVersion, boolean isSaliently) {
        if (resCheckVersion.getVersionCode() > versionCode) {
            AlertDialog.Builder builder;
            if (dialogTheme != 0 && Build.VERSION.SDK_INT > 11) {
                builder = new AlertDialog.Builder(activity, dialogTheme);
            } else {
                builder = new AlertDialog.Builder(activity);
            }

            builder.setTitle(R.string.dialog_update_title)
                    .setMessage(activity.getString(R.string.dialog_update_msg, resCheckVersion.getVersionName()))
                    .setPositiveButton(R.string.dialog_update_btnupdate, (dialog, which) -> {
                        updateStatus(Status.NORMAL);
                        download(activity, downloadUrl, resCheckVersion.getVersionName());
                    })
                    .setNegativeButton(R.string.dialog_update_btnnext, (dialog, which) -> {
                        activity.finish();
                        updateStatus(Status.NORMAL);
                    })
                    .setOnCancelListener(dialog -> updateStatus(Status.NORMAL))
                    .show();
        } else if (!isSaliently) {
            updateStatus(Status.NORMAL);
            ToastHelper.get(activity).showShort(R.string.toast_last_version);
        } else {
            updateStatus(Status.NORMAL);
        }
    }

    public void downLoadDebug(Activity activity, String url) {
        downLoadDebug(activity, url, "debug");
    }

    public void downLoadDebug(Activity activity, String url, String versionName) {
        download(activity, url, versionName);
    }

    public void cancelDownLoad() {
        File apkFile = new File(downloadAppName);
        if (apkFile.exists() && apkFile.delete()) {
            Timber.d("cancel download and delete apk files");
        }
        reset();
        subscription.clear();
    }

    private void reset() {
        updateStatus(Status.NORMAL);
        DownLoadProgress downLoadProgress = new DownLoadProgress();
        sendProgress(downLoadProgress);
        updateDownloadProgress(downLoadProgress);
    }

    private void update() {
        updateProgressDialog.dismiss();
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setDataAndType(
                Uri.fromFile(new File(downloadAppName)),
                "application/vnd.android.package-archive");
        activity.startActivity(intent);
        reset();
    }

    public static class DownLoadProgress {
        public long progress = 0;
        public long totalLength = 1;

        public boolean isComplete() {
            return progress == totalLength;
        }

        public long getProgress() {
            return progress;
        }

        public DownLoadProgress setProgress(long progress) {
            this.progress = progress;
            return this;
        }

        public long getTotalLength() {
            return totalLength;
        }

        public DownLoadProgress setTotalLength(long totalLength) {
            this.totalLength = totalLength;
            return this;
        }

        public DownLoadProgress reset() {
            this.progress = 0;
            this.totalLength = 1;
            return this;
        }
    }


    public Observable<DownLoadProgress> get(String url) {
        return Observable.create(new Observable.OnSubscribe<DownLoadProgress>() {
            @Override
            public void call(Subscriber<? super DownLoadProgress> subscriber) {
                Request request = new Request.Builder().url(url).build();
                Response response = null;
                File apkFile = new File(downloadAppName);
                if (apkFile.exists()) {
                    Timber.d("apk file is exist and install it directly");
                    handler.sendEmptyMessage(RESET);
                    return;
                }
                FileOutputStream fos = null;
                InputStream inputStream = null;
                UpdateManager.DownLoadProgress downLoadProgress = new UpdateManager.DownLoadProgress();
                try {
                    fos = new FileOutputStream(apkFile);
                    response = client.newCall(request).execute();

                    if (response.code() == 200) {
                        inputStream = response.body().byteStream();
                        byte[] buff = new byte[1024 * 4];
                        long downloaded = 0;
                        long target = response.body().contentLength();
                        downLoadProgress.setTotalLength(target);
                        subscriber.onStart();
                        subscriber.onNext(downLoadProgress.setProgress(0));
                        int hasRead = 0;
                        long lastUpdate = System.currentTimeMillis();
                        while ((hasRead = inputStream.read(buff)) != -1) {
                            downloaded += hasRead;
                            fos.write(buff, 0, hasRead);

                            if (System.currentTimeMillis() - lastUpdate > 100) {
                                lastUpdate = System.currentTimeMillis();
                                subscriber.onNext(downLoadProgress.setProgress(downloaded));
                            }
                        }
                        subscriber.onNext(downLoadProgress.setProgress(target));
                        subscriber.onCompleted();
                    } else if (response.code() == 404) {
                        subscriber.onError(new IOException(RES_404));
                    } else {
                        subscriber.onError(new IOException());
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    subscriber.onError(e);
                } finally {
                    if (inputStream != null) {
                        try {
                            inputStream.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
    }

    public UpdateManager setOnUpdateStatus(OnUpdateStatus onUpdateStatus) {
        this.onUpdateStatus.add(onUpdateStatus);
        return this;
    }

    public void removeOnUpdateStatus(OnUpdateStatus updateStatus) {
        onUpdateStatus.remove(updateStatus);
    }

    public void setOnUpdateProgress(OnUpdateProgress onUpdateProgress) {
        this.onUpdateProgress.add(onUpdateProgress);
    }

    public void removeUpdateDownloadProgress(DownLoadProgress progress) {
        onUpdateProgress.remove(progress);
    }

    public interface OnUpdateStatus {
        void onStatusChanged(Status status);
    }

    public interface OnUpdateProgress {
        void onProgressChanged(DownLoadProgress downLoadProgress);
    }

}
