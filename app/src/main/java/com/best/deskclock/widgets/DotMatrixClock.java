// SPDX-License-Identifier: GPL-3.0-only

package com.best.deskclock.widgets;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.best.deskclock.R;
import com.best.deskclock.data.WidgetDAO;
import com.best.deskclock.utils.SdkUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * OmniClock: draws the widget time as Nothing-style dot-matrix digits.
 *
 * <p>Some launchers (for example Huawei) ignore fonts bundled inside an app, so the time is
 * rendered here into an image with the Doto font and shown on top of the (invisible) TextClock,
 * which keeps its size so the layout stays the same. Because an image does not tick by itself,
 * the widgets are refreshed once per minute.</p>
 *
 * <p>If the font cannot be loaded, nothing is changed and the normal TextClock stays visible.</p>
 */
public final class DotMatrixClock {

    private static final String FONT_ASSET = "fonts/doto.ttf";
    private static final String ACTION_MINUTE_TICK = "app.omniclock.action.WIDGET_MINUTE_TICK";
    private static final int REQUEST_CODE_MINUTE_TICK = 7301;

    /** Text size used for drawing; the launcher scales the image to the clock area. */
    private static final float TEXT_SIZE_PX = 160f;

    private static Typeface sTypeface;
    private static boolean sTypefaceLoaded;

    private DotMatrixClock() {
    }

    /**
     * Digital widget: one image with the full time (e.g. "13:41").
     */
    public static void applyDigital(@NonNull Context context, @NonNull SharedPreferences prefs, @NonNull RemoteViews rv,
                                    int clockViewId, int clockCustomViewId) {

        final boolean isDefaultColor = WidgetDAO.isDigitalWidgetDefaultClockColor(prefs);
        final int color = isDefaultColor
            ? ContextCompat.getColor(context, R.color.digital_widget_time_color)
            : WidgetDAO.getDigitalWidgetCustomClockColor(prefs);
        final String pattern = DateFormat.is24HourFormat(context) ? "HH:mm" : "h:mm";

        if (apply(context, rv, R.id.clockDots, isDefaultColor ? clockViewId : clockCustomViewId, format(pattern), color)) {
            scheduleNextMinute(context);
        }
    }

    /**
     * Vertical widget: one image for the hours and one for the minutes.
     */
    public static void applyVertical(@NonNull Context context, @NonNull SharedPreferences prefs, @NonNull RemoteViews rv,
                                     int hoursViewId, int hoursCustomViewId, int minutesViewId, int minutesCustomViewId) {

        final boolean isDefaultHoursColor = WidgetDAO.isVerticalWidgetDefaultHoursColor(prefs);
        final int hoursColor = isDefaultHoursColor
            ? ContextCompat.getColor(context, R.color.vertical_widget_hour_color)
            : WidgetDAO.getVerticalWidgetCustomHoursColor(prefs);

        final boolean isDefaultMinutesColor = WidgetDAO.isVerticalWidgetDefaultMinutesColor(prefs);
        final int minutesColor = isDefaultMinutesColor
            ? ContextCompat.getColor(context, R.color.vertical_widget_minute_color)
            : WidgetDAO.getVerticalWidgetCustomMinutesColor(prefs);

        final String hoursPattern = DateFormat.is24HourFormat(context) ? "HH" : "hh";

        final boolean hoursDone = apply(context, rv, R.id.clockHoursDots,
            isDefaultHoursColor ? hoursViewId : hoursCustomViewId, format(hoursPattern), hoursColor);
        final boolean minutesDone = apply(context, rv, R.id.clockMinutesDots,
            isDefaultMinutesColor ? minutesViewId : minutesCustomViewId, format("mm"), minutesColor);

        if (hoursDone || minutesDone) {
            scheduleNextMinute(context);
        }
    }

    private static boolean apply(@NonNull Context context, @NonNull RemoteViews rv, int dotsViewId, int clockViewId,
                                 @NonNull String text, int color) {

        if (dotsViewId == 0 || clockViewId == 0) {
            return false;
        }

        final Bitmap bitmap = render(context, text, color);
        if (bitmap == null) {
            return false;
        }

        rv.setImageViewBitmap(dotsViewId, bitmap);
        rv.setViewVisibility(dotsViewId, View.VISIBLE);
        // Keep the TextClock's size in the layout, but hide its (non dot-matrix) digits.
        rv.setViewVisibility(clockViewId, View.INVISIBLE);
        rv.setContentDescription(dotsViewId, text);
        return true;
    }

    @Nullable
    private static Bitmap render(@NonNull Context context, @NonNull String text, int color) {
        final Typeface typeface = getTypeface(context);
        if (typeface == null) {
            return null;
        }

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(typeface);
        paint.setTextSize(TEXT_SIZE_PX);
        paint.setColor(color);

        // Use the height of a digit so hours and minutes get the same scale.
        final Rect digitBounds = new Rect();
        paint.getTextBounds("0", 0, 1, digitBounds);

        final int padding = Math.round(TEXT_SIZE_PX * 0.04f);
        final int width = (int) Math.ceil(paint.measureText(text)) + padding * 2;
        final int height = digitBounds.height() + padding * 2;
        if (width <= 0 || height <= 0) {
            return null;
        }

        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        canvas.drawText(text, padding, padding - digitBounds.top, paint);
        return bitmap;
    }

    @Nullable
    private static synchronized Typeface getTypeface(@NonNull Context context) {
        if (!sTypefaceLoaded) {
            sTypefaceLoaded = true;
            try {
                sTypeface = Typeface.createFromAsset(context.getAssets(), FONT_ASSET);
            } catch (RuntimeException e) {
                sTypeface = null;
            }
        }
        return sTypeface;
    }

    @NonNull
    private static String format(@NonNull String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(new Date());
    }

    /**
     * Refreshes the digital and vertical widgets at the start of the next minute.
     * The alarm does not wake the phone; it is delivered as soon as the screen is on.
     */
    private static void scheduleNextMinute(@NonNull Context context) {
        final AlarmManager alarmManager = context.getApplicationContext().getSystemService(AlarmManager.class);
        if (alarmManager == null) {
            return;
        }

        final Intent intent = new Intent(context, DailyWidgetUpdateReceiver.class).setAction(ACTION_MINUTE_TICK);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE_MINUTE_TICK, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        final long now = System.currentTimeMillis();
        final long nextMinute = (now / 60_000L + 1) * 60_000L;

        try {
            if (SdkUtils.isAtLeastAndroid12() && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.set(AlarmManager.RTC, nextMinute, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC, nextMinute, pendingIntent);
            }
        } catch (SecurityException e) {
            alarmManager.set(AlarmManager.RTC, nextMinute, pendingIntent);
        }
    }

}
