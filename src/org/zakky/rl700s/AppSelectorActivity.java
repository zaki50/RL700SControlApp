
package org.zakky.rl700s;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * アプリを選択するアクティビティです。
 */
public class AppSelectorActivity extends Activity implements OnItemClickListener {
    public static final String TAG = "RL700S";

    private static final int[] ICON_SIZE_CONFIG = {
            makeConfig(960, 72), makeConfig(800, 60), makeConfig(480, 44), makeConfig(0, 32),
    };

    private static int makeConfig(int border, int size) {
        if (border < 0 || size < 0) {
            throw new RuntimeException("unexpected icon size config. border=" + border + ", size="
                    + size);
        }
        final int config = (border << 16) | (size & 0xFFFF);
        return config;
    }

    private static int getBorderFromConfig(int config) {
        return (config >> 16) & 0xFFFF;
    }

    private static int getSizeFromConfig(int config) {
        return (config & 0xFFFF);
    }

    /**
     * アプリ一覧表示用グリッド。
     */
    private GridView appGrid_;

    /**
     * アプリ一覧グリッド構築時のプログレス
     * <p>
     * UI スレッドからのみアクセスすること。
     * </p>
     */
    private ProgressDialog progressDialog_ = null;

    /**
     * アプリ一覧のグリッドを用意します。
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
         * アプリ一覧をユーザに提示するためのグリッドを用意します。
         */
        setContentView(R.layout.grid);

        appGrid_ = (GridView) findViewById(R.id.grid);
        appGrid_.setOnItemClickListener(this);
    }

    /**
     * アクティビティ開始処理として、アプリ一覧を取得してグリッドにセットするためのタスクを 実行します。
     */
    @Override
    protected void onStart() {
        super.onStart();

        final LoadAppListTask task = new LoadAppListTask();
        task.execute();
        startProgress();
    }

    @Override
    protected void onStop() {
        super.onStop();

        dismissProgress();
    }

    /**
     * プログレスダイアログを作成して表示します。
     */
    private void startProgress() {
        dismissProgress();

        final ProgressDialog progress = new ProgressDialog(this);
        final String message = getString(R.string.shortcut_progressdialog_title);
        progress.setMessage(message);
        progress.setCancelable(false);
        progress.show();

        progressDialog_ = progress;
    }

    /**
     * プログレスダイアログが表示されていれば中止します。
     * <p>
     * このメソッドが正常に完了した後は、 {@link #progressDialog_} が {@code null} に なります。
     * </p>
     */
    private void dismissProgress() {
        final ProgressDialog progress = progressDialog_;
        if (progress != null) {
            progress.dismiss();
        }
        progressDialog_ = null;
    }

    /**
     * アプリ一覧で、あるアプリがクリックされたときのアクションです。
     */
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        final AppInfo appInfo = (AppInfo) parent.getItemAtPosition(position);
        BitmapDrawable icon = (BitmapDrawable) appInfo.getIcon();

        final Bitmap bmp = Bitmap.createBitmap(320, 320, Config.ARGB_8888);
        final Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint();
        paint.setDither(true);
        canvas.drawBitmap(((BitmapDrawable) icon).getBitmap(), null, new Rect(0, 0,
                bmp.getWidth() - 1, bmp.getHeight() - 1), paint);
        final int[] pixels = new int[320 * 320];
        bmp.getPixels(pixels, 0, 320, 0, 0, 320, 320);

        // Random Dithering で二値化する
        final Random rand = new Random();
        for (int i = 0; i < pixels.length; i++) {
            final int p = pixels[i];
            // y=0.587*g+0.299*r+0.114b
            final int y = Color.green(p) * 587 / 1000 //
                    + Color.red(p) * 299 / 1000 //
                    + Color.blue(p) * 114 / 1000;

            final int blackOrWhite = (y < rand.nextInt(256)) ? 0 : 255;
            pixels[i] = Color.argb(0xff, blackOrWhite, blackOrWhite, blackOrWhite);
        }

        // 二値化されたデータを印刷用ラスターデータに変換する
        // TODO 二値化の際に変換も一緒に行ったほうが効率的
        final int bmpWidth = bmp.getWidth();
        final int bmpHeight = bmp.getHeight();
        final int black = Color.argb(0xff, 0, 0, 0);
        byte[][] rasterData = new byte[bmpWidth][];
        for (int w = 0; w < rasterData.length; w++) {
            final byte[] line = new byte[4/*印刷されない領域分*/+ bmp.getHeight() / 8];
            int d = 0;
            for (int h = 0; h < bmpHeight; h++) {
                final int index = h * bmpWidth + w;
                d <<= 1;
                d += (pixels[index] == black ? 1 : 0);
                if (h % 8 == 7) {
                    line[4 + h / 8] = (byte) d;
                }
            }
            rasterData[w] = line;
        }

        final Intent intent = new Intent(this, PrintActivity.class);
        intent.putExtra("data", rasterData);
        startActivity(intent);
    }

    /**
     * アプリ一覧を取得し、 {@value CreateShortcutActivity#appGrid_} にセットするタスクです。
     * <p>
     * 取得処理中はキャンセル不可なプログレスダイアログを表示します。
     * </p>
     *
     * @author zaki
     */
    private final class LoadAppListTask extends AsyncTask<Void, Void, List<AppInfo>> {

        @Override
        protected List<AppInfo> doInBackground(Void... v) {
            final Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

            final PackageManager pm = getPackageManager();
            final List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
            final List<AppInfo> appList = new ArrayList<AppInfo>(apps.size());
            for (ResolveInfo info : apps) {
                final String packageName = info.activityInfo.packageName;
                if (packageName == null) {
                    continue;
                }
                final String activityFqcn = info.activityInfo.name;
                final CharSequence label = info.loadLabel(pm);
                final Drawable icon = info.activityInfo.loadIcon(pm);

                final AppInfo appInfo = new AppInfo(label.toString(), icon, activityFqcn,
                        packageName);
                appList.add(appInfo);
            }
            Collections.sort(appList, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo app1, AppInfo app2) {
                    return app1.getLabel().compareTo(app2.getLabel());
                }
            });

            return appList;
        }

        @Override
        protected final void onPostExecute(List<AppInfo> appList) {
            final AppsAdapter adapter = new AppsAdapter(getApplicationContext(), appList);
            appGrid_.setAdapter(adapter);
            dismissProgress();
        }
    }

    /**
     * アプリ一覧に表示される１つのアプリの情報を保持するクラスです。
     *
     * @author zaki
     */
    private static final class AppInfo {
        /** アプリケーションのラベル */
        private final String label_;

        /** アプリケーションのアイコン */
        private final Drawable icon_;

        /** アプリケーションの FQCN */
        private final String activityFqcn_;

        /** アプリケーションのパッケージ名 */
        private final String packageName_;

        public AppInfo(String label, Drawable icon, String activityFqcn, String packageName) {
            super();
            label_ = label;
            icon_ = icon;
            activityFqcn_ = activityFqcn;
            packageName_ = packageName;
        }

        /**
         * アプリケーションのラベルを返します。
         *
         * @return ラベル。
         */
        public String getLabel() {
            return label_;
        }

        /**
         * アプリケーションのアイコンを返します。
         *
         * @return アイコン。
         */
        public Drawable getIcon() {
            return icon_;
        }

        /**
         * アプリケーションの FQCN を返します。
         *
         * @return FQCN
         */
        @SuppressWarnings("unused")
        public String getActivityFqcn() {
            return activityFqcn_;
        }

        /**
         * アプリケーションのパッケージ名を返します。
         *
         * @return パッケージ名。
         */
        @SuppressWarnings("unused")
        public String getPackageName() {
            return packageName_;
        }

    }

    /**
     * {@link GridView} に対してアプリ一覧を提供するアダプタです。
     *
     * @author zaki
     */
    public static final class AppsAdapter extends BaseAdapter {

        /**
         * アプリ一覧
         */
        private final List<AppInfo> apps_;

        /**
         * グリッドの要素を生成するためのインフレータ。
         */
        private final LayoutInflater inflater_;

        /**
         * グリッドにアプリアイコンを表示する際のサイズを保持するパラメータ。
         */
        private final LinearLayout.LayoutParams params_;

        /**
         * 指定されたアプリ一覧を提供する {@link AppsAdapter} を構築します。
         *
         * @param appContext アプリケーションコンテキスト。 コンストラクタ内でのみ使用し、参照は保持しません。
         * @param apps 表示するアプリケーションのリスト。
         *            渡されたリストは、アダプター内で保持します。以降呼び出し側で変更しないことを前提にしています。
         */
        public AppsAdapter(Context appContext, List<AppInfo> apps) {
            apps_ = apps;
            inflater_ = (LayoutInflater) appContext
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            // 画面サイズを取得
            final int iconSize = getIconSize(appContext.getWallpaperDesiredMinimumWidth(),
                    appContext.getWallpaperDesiredMinimumHeight());
            params_ = new LinearLayout.LayoutParams(iconSize, iconSize);
        }

        /**
         * 画面の大きさから、適切なアイコンのピクセル数を決定します。
         *
         * @param wallpaperWidth 画面の幅。
         * @param wallpaperHeight 画面の高さ。
         * @return アイコン画像のいっぺんのピクセル数。
         */
        private static int getIconSize(int wallpaperWidth, int wallpaperHeight) {
            for (int config : ICON_SIZE_CONFIG) {
                final int border = getBorderFromConfig(config);
                if (wallpaperHeight < border) {
                    continue;
                }
                final int size = getSizeFromConfig(config);
                return size;
            }
            throw new RuntimeException("failed to determine icon size. wallpaperWidth="
                    + wallpaperWidth + ", wallpaperHeight=" + wallpaperHeight);
        }

        /**
         * アプリ1つ分を表現する {@link View} を返します。
         *
         * @param position アイテムのインデックス。 0 ベース。
         * @param convertView これまで使用されていた {@link View} オブジェクト。 {@code null}
         *            の可能性あり。 可能なかぎり再利用すること。
         * @param parent 対象とする {@link View} の親。
         * @return {@link View} オブジェクト。
         */
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = (convertView == null) ? inflater_.inflate(R.layout.grid_row, null)
                    : convertView;
            final GridRowData rowData = (v.getTag() == null) ? createRowData(v) : (GridRowData) v
                    .getTag();

            final AppInfo info = getItem(position);
            rowData.getTextView().setText(info.getLabel());
            rowData.getImageView().setImageDrawable(info.getIcon());

            v.setTag(rowData);
            return v;
        }

        /**
         * グリッド内の UI コンポーネントを取り出し、 {@link GridRowData} として返します。
         *
         * @param rowView アプリ1つ分のView。
         * @return {@link View} から取り出した UI コンポーネントを保持する {@link GridRowData}。
         */
        private GridRowData createRowData(View rowView) {
            final TextView text = (TextView) rowView.findViewById(R.id.grid_row_txt);
            final ImageView image = (ImageView) rowView.findViewById(R.id.grid_row_img);
            image.setLayoutParams(params_);

            final GridRowData rowData = new GridRowData(text, image);
            return rowData;
        }

        /**
         * アダプタが保持するアプリの数を返します。
         */
        public final int getCount() {
            return apps_.size();
        }

        /**
         * 指定されたインデックスの {@link AppInfo} を返します。
         *
         * @return 指定されたインデックスに対応する {@link AppInfo}。
         * @throws IndexOutOfBoundsException 指定されたインデックスが、 {@code 0} 以上
         *             {@link #getCount()} 未満の範囲かた外れている場合。
         */
        public final AppInfo getItem(int position) {
            if (position < 0 || getCount() <= position) {
                throw new IndexOutOfBoundsException();
            }
            return apps_.get(position);
        }

        /**
         * インデックスをアイテムIDに変換します。
         */
        public final long getItemId(int position) {
            return position;
        }

        /**
         * グリッドに属するUIコンポーネントを束ねるクラスです。
         * <p>
         * グリッドのセル１つを構成する {@link View} に簡単にアクセスできるように、 関係する {@link View}
         * をメンバ変数として保持するクラスです。
         * <p>
         *
         * @author zaki
         */
        private static final class GridRowData {
            /**
             * アプリのラベルを保持する {@link View}。
             */
            private final TextView text_;

            /**
             * アプリのアイコンを保持する {@link View}。
             */
            private final ImageView image_;

            public GridRowData(TextView text, ImageView image) {
                if (text == null) {
                    throw new IllegalArgumentException("'text' must not be null");
                }
                if (image == null) {
                    throw new IllegalArgumentException("'image' must not be null");
                }
                text_ = text;
                image_ = image;
            }

            /**
             * アプリラベルを保持する {@link View} を返します。
             *
             * @return {@link TextView}
             */
            public TextView getTextView() {
                return text_;
            }

            /**
             * アプリアイコンを保持する {@link View} を返します。
             *
             * @return {@link ImageView}
             */
            public ImageView getImageView() {
                return image_;
            }
        }

    }

}
