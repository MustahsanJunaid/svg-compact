/*
    Copyright 2011, 2015 Pixplicity, Larva Labs LLC and Google, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

    SvgCompact is heavily based on prior work. It was originally forked from
        https://github.com/pents90/svg-android
    And changes from other forks have been consolidated:
        https://github.com/b2renger/svg-android
        https://github.com/mindon/svg-android
        https://github.com/josefpavlik/svg-android
 */

package com.android.kit.svg;

import android.annotation.TargetApi;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Shader.TileMode;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Looper;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.text.TextPaint;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.kit.svg.model.Gradient;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Entry point for parsing SVG files for Android.
 * Use one of the various static methods for parsing SVGs by resource, asset or input stream.
 * Optionally, a single color can be searched and replaced in the SVG while parsing.
 * You can also parse an svg path directly.
 *
 * @author Larva Labs, LLC
 * @see #loadResource(Resources, int)
 * @see #loadAsset(AssetManager, String)
 * @see #loadString(String)
 * @see #loadInputStream(InputStream)
 * @see #loadPath(String)
 */
public abstract class SvgCompact {

    static final String TAG = SvgCompact.class.getSimpleName();

    private HashMap<Integer, Integer> colorMap = new HashMap();

    public static final int LOG_LEVEL_ERROR = 1;
    public static final int LOG_LEVEL_WARN = 2;
    public static final int LOG_LEVEL_INFO = 3;
    static int LOG_LEVEL = LOG_LEVEL_ERROR;

    @IntDef({LOG_LEVEL_ERROR, LOG_LEVEL_WARN, LOG_LEVEL_INFO})
    public @interface LogLevel {
    }

    private static String sAssumedUnit;
    private static HashMap<String, String> textDynamic = null;

    private final SvgHandler mSvgHandler;

    private OnSvgElementListener mOnElementListener;
    private AssetManager mAssetManager;

    enum Unit {
        PERCENT("%"),
        PT("pt"),
        PX("px"),
        MM("mm", 100);

        public final String mAbbreviation;
        public final float mScaleFactor;

        Unit(String abbreviation) {
            this(abbreviation, 1f);
        }

        Unit(String abbreviation, float scaleFactor) {
            mAbbreviation = abbreviation;
            mScaleFactor = scaleFactor;
        }

        public static Unit matches(String value) {
            for (Unit unit : Unit.values()) {
                if (value.endsWith(unit.mAbbreviation)) {
                    return unit;
                }
            }
            return null;
        }
    }

    public static void setLogLevel(@LogLevel int logLevel) {
        LOG_LEVEL = logLevel;
    }

    @SuppressWarnings("unused")
    public static void prepareTexts(HashMap<String, String> texts) {
        textDynamic = texts;
    }

    /**
     * Parse SVG data from an input stream.
     *
     * @param svgData the input stream, with SVG XML data in UTF-8 character encoding.
     * @return this SvgCompact object
     */
    @SuppressWarnings("unused")
    public static SvgCompact loadInputStream(final InputStream svgData) {
        return new SvgCompact() {
            protected InputStream getInputStream() {
                return svgData;
            }

            @Override
            protected void close(InputStream inputStream) {
            }
        };
    }

    /**
     * Parse SVG data from a text.
     *
     * @param svgData the text containing SVG XML data.
     * @return this SvgCompact object
     */
    @SuppressWarnings("unused")
    public static SvgCompact loadString(final String svgData) {
        return new SvgCompact() {
            protected InputStream getInputStream() {
                return new ByteArrayInputStream(svgData.getBytes());
            }

            @Override
            protected void close(InputStream inputStream) {
            }
        };
    }

    /**
     * Parse SVG data from an Android application resource.
     *
     * @param resources the Android context resources.
     * @param resId     the ID of the raw resource SVG.
     * @return this SvgCompact object
     */
    @SuppressWarnings("unused")
    public static SvgCompact loadResource(final Resources resources,
                                          final int resId) {
        return new SvgCompact() {
            protected InputStream getInputStream() {
                InputStream inputStream = resources.openRawResource(resId);
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    // Read the contents of the resource if we're not on the main thread
                    inputStream = readInputStream(inputStream);
                }
                return inputStream;
            }

            @Override
            protected void close(InputStream inputStream) {
            }
        };
    }

    /**
     * Parse SVG data from an Android application asset.
     *
     * @param assetMngr the Android asset manager.
     * @param svgPath   the path to the SVG file in the application's assets.
     * @return this SvgCompact object
     */
    @SuppressWarnings("unused")
    public static SvgCompact loadAsset(final AssetManager assetMngr,
                                       final String svgPath) {
        return new SvgCompact() {
            protected InputStream getInputStream() throws IOException {
                InputStream inputStream = assetMngr.open(svgPath);
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    // Read the contents of the resource if we're not on the main thread
                    inputStream = readInputStream(inputStream);
                }
                return inputStream;
            }

            @Override
            protected void close(InputStream inputStream) throws IOException {
                inputStream.close();
            }
        };
    }

    /**
     * Parse SVG data from a file.
     *
     * @param imageFile the input stream, with SVG XML data in UTF-8 character encoding.
     * @return this SvgCompact object
     */
    @SuppressWarnings("unused")
    public static SvgCompact loadFile(final File imageFile) {
        return new SvgCompact() {
            private FileInputStream mFis;

            protected InputStream getInputStream() throws FileNotFoundException {
                mFis = new FileInputStream(imageFile);
                return mFis;
            }

            @Override
            protected void close(InputStream inputStream) throws IOException {
                inputStream.close();
                mFis.close();
            }
        };
    }

    /**
     * Parses a single SVG path and returns it as a <code>android.graphics.Path</code> object.
     * An example path is <code>M250,150L150,350L350,350Z</code>, which draws a triangle.
     *
     * @param pathString the SVG path, see the specification <a href="http://www.w3.org/TR/SVG/paths.html">here</a>.
     */
    @SuppressWarnings("unused")
    public static Path loadPath(String pathString) {
        return doPath(pathString);
    }

    @NonNull
    private static InputStream readInputStream(InputStream inputStream) {
        StringBuilder svgData = new StringBuilder();
        Scanner scanner = new Scanner(inputStream);
        String lineSeparator = System.getProperty("line.separator");
        // Try-with-resources is only for API level 19 and up
        //noinspection TryFinallyCanBeTryWithResources
        try {
            while (scanner.hasNextLine()) {
                svgData.append(scanner.nextLine()).append(lineSeparator);
            }
        } finally {
            scanner.close();
        }
        inputStream = new ByteArrayInputStream(svgData.toString().getBytes());
        return inputStream;
    }

    private SvgCompact() {
        //Log.d(TAG, "Parsing SVG...");
        sAssumedUnit = null;
        mSvgHandler = new SvgHandler(this);
    }

    private AssetManager getAssetManager() {
        return mAssetManager;
    }

    @SuppressWarnings("unused")
    public SvgCompact setOnElementListener(OnSvgElementListener onElementListener) {
        mOnElementListener = onElementListener;
        return this;
    }

    protected abstract InputStream getInputStream() throws IOException;

    protected abstract void close(InputStream inputStream) throws IOException;

    @SuppressWarnings("unused")
    public SvgCompact withAssets(AssetManager assetManager) {
        mAssetManager = assetManager;
        return this;
    }

    public SvgCompact withColorMap(HashMap<Integer, Integer> colorMap) {
        this.colorMap = colorMap;
        return this;
    }

    @SuppressWarnings("unused")
    public void into(@NonNull final View view) {
        SvgDrawable.prepareView(view);
        if (view instanceof ImageView) {
            final SvgDrawable drawable = getDrawable();
            if (Looper.myLooper() == Looper.getMainLooper()) {
                // Set it immediately if on the main thread
                ((ImageView) view).setImageDrawable(drawable);
            } else {
                // Otherwise, set it on through the view's Looper
                view.post(new Runnable() {
                    @Override
                    public void run() {
                        ((ImageView) view).setImageDrawable(drawable);
                    }
                });
            }
        } else {
            intoBackground(view);
        }
    }

    @SuppressWarnings("deprecation")
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void intoBackground(final View view) {
        final SvgDrawable drawable = getDrawable(view);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Set it immediately if on the main thread
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
                view.setBackgroundDrawable(drawable);
            } else {
                view.setBackground(drawable);
            }
        } else {
            // Otherwise, set it on through the view's Looper
            view.post(new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
                        view.setBackgroundDrawable(drawable);
                    } else {
                        view.setBackground(drawable);
                    }
                }
            });
        }
    }

    /**
     * Processes the SVG and provides the resulting drawable. Runs on the main thread.
     */
    @SuppressWarnings("unused")
    public SvgDrawable getDrawable() {
        return getSvgPicture().getDrawable();
    }

    /**
     * Processes the SVG and provides the resulting drawable. Runs on the main thread.
     *
     * @deprecated Use {@link #getDrawable()} instead.
     */
    @Deprecated
    @SuppressWarnings("unused")
    public SvgDrawable getDrawable(View view) {
        return getSvgPicture().getDrawable(view);
    }

    /**
     * Processes the SVG and provides the resulting drawable. Runs in a background thread.
     */
    @SuppressWarnings("unused")
    public void getDrawable(final View view, final DrawableCallback callback) {
        getSvgPicture(new PictureCallback() {
            @Override
            public void onPictureReady(SvgPicture svgPicture) {
                SvgDrawable drawable = svgPicture.getDrawable(view);
                callback.onDrawableReady(drawable);
            }
        });
    }

    private SvgPicture getSvgPicture(InputStream inputStream) throws SvgParseException {
        if (inputStream == null) {
            throw new NullPointerException("An InputStream must be provided");
        }
        try {
            mSvgHandler.read(inputStream);
        } finally {
            try {
                close(inputStream);
            } catch (IOException e) {
                //noinspection ThrowFromFinallyBlock
                throw new SvgParseException(e);
            }
        }
        SvgPicture result = new SvgPicture(mSvgHandler.picture, mSvgHandler.bounds);
        // Skip bounds if it was an empty pic
        if (!Float.isInfinite(mSvgHandler.limits.top)) {
            result.setLimits(mSvgHandler.limits);
        }
        return result;
    }

    @SuppressWarnings("unused")
    public SvgPicture getSvgPicture() throws SvgParseException {
        InputStream inputStream = null;
        try {
            inputStream = getInputStream();
            return getSvgPicture(inputStream);
        } catch (IOException e) {
            throw new SvgParseException(e);
        } finally {
            try {
                if (inputStream != null) {
                    close(inputStream);
                }
            } catch (IOException e) {
                //noinspection ThrowFromFinallyBlock
                throw new SvgParseException(e);
            }
        }
    }

    public void getSvgPicture(final PictureCallback callback) {
        new AsyncTask<Void, Void, SvgPicture>() {
            @Override
            protected SvgPicture doInBackground(Void... params) {
                InputStream inputStream = null;
                try {
                    inputStream = getInputStream();
                    return getSvgPicture(inputStream);
                } catch (IOException e) {
                    throw new SvgParseException(e);
                } finally {
                    try {
                        if (inputStream != null) {
                            close(inputStream);
                        }
                    } catch (IOException e) {
                        //noinspection ThrowFromFinallyBlock
                        throw new SvgParseException(e);
                    }
                }
            }

            @Override
            protected void onPostExecute(SvgPicture svgPicture) {
                callback.onPictureReady(svgPicture);
            }
        }.execute();
    }

    private static ArrayList<Float> parseNumbers(String s) {
        //Log.d(TAG, "Parsing numbers from: '" + s + "'");
        int n = s.length();
        int p = 0;
        ArrayList<Float> numbers = new ArrayList<>();
        boolean skipChar = false;
        for (int i = 1; i < n; i++) {
            if (skipChar) {
                skipChar = false;
                continue;
            }
            char c = s.charAt(i);
            switch (c) {
                // This ends the parsing, as we are on the next element
                case 'M':
                case 'm':
                case 'Z':
                case 'z':
                case 'L':
                case 'l':
                case 'H':
                case 'h':
                case 'V':
                case 'v':
                case 'C':
                case 'c':
                case 'S':
                case 's':
                case 'Q':
                case 'q':
                case 'T':
                case 't':
                case 'a':
                case 'A':
                case ')': {
                    String str = s.substring(p, i);
                    if (str.trim().length() > 0) {
                        //Log.d(TAG, "  Last: " + str);
                        Float f = Float.parseFloat(str);
                        numbers.add(f);
                    }
                    return numbers;
                }
                case 'e':
                case 'E': {
                    // exponent in float number - skip eventual minus sign following the exponent
                    skipChar = true;
                    break;
                }
                case '\n':
                case '\t':
                case ' ':
                case ',':
                case '-': {
                    String str = s.substring(p, i);
                    // Just keep moving if multiple whitespace
                    if (str.trim().length() > 0) {
                        //Log.d(TAG, "  Next: " + str);
                        Float f = Float.parseFloat(str);
                        numbers.add(f);
                        if (c == '-') {
                            p = i;
                        } else {
                            p = i + 1;
                            skipChar = true;
                        }
                    } else {
                        p++;
                    }
                    break;
                }
            }
        }
        String last = s.substring(p);
        if (last.length() > 0) {
            //Log.d(TAG, "  Last: " + last);
            try {
                numbers.add(Float.parseFloat(last));
            } catch (NumberFormatException nfe) {
                // Just white-space, forget it
            }
        }
        return numbers;
    }

    private static ArrayList<Float> readTransform(String attr, String type) {
        int i = attr.indexOf(type + "(");
        if (i > -1) {
            i += type.length() + 1;
            int j = attr.indexOf(")", i);
            if (j > -1) {
                ArrayList<Float> np = parseNumbers(attr.substring(i, j));
                if (np.size() > 0) {
                    return np;
                }
            }
        }
        return null;
    }

    @Nullable
    private static Matrix parseTransform(String s) {
        Matrix matrix = null;

        if (s.startsWith("matrix(")) {
            ArrayList<Float> np = parseNumbers(s.substring("matrix(".length()));
            if (np.size() == 6) {
                //noinspection ConstantConditions
                if (matrix == null) {
                    matrix = new Matrix();
                }
                matrix.setValues(new float[]{
                        // Row 1
                        np.get(0),
                        np.get(2),
                        np.get(4),
                        // Row 2
                        np.get(1),
                        np.get(3),
                        np.get(5),
                        // Row 3
                        0,
                        0,
                        1,
                });
            }
        }

        ArrayList<Float> np = readTransform(s, "scale");
        if (np != null) {
            float sx = np.get(0);
            float sy = sx;
            if (np.size() > 1) {
                sy = np.get(1);
            }
            if (matrix == null) {
                matrix = new Matrix();
            }
            matrix.postScale(sx, sy);
        }

        np = readTransform(s, "skewX");
        if (np != null) {
            float angle = np.get(0);
            if (matrix == null) {
                matrix = new Matrix();
            }
            matrix.preSkew((float) Math.tan(angle), 0);
        }

        np = readTransform(s, "skewY");
        if (np != null) {
            float angle = np.get(0);
            if (matrix == null) {
                matrix = new Matrix();
            }
            matrix.preSkew(0, (float) Math.tan(angle));
        }

        np = readTransform(s, "rotate");
        if (np != null) {
            float angle = np.get(0);
            float cx, cy;
            if (matrix == null) {
                matrix = new Matrix();
            }
            if (np.size() > 2) {
                cx = np.get(1);
                cy = np.get(2);
                matrix.preRotate(angle, cx, cy);
            } else {
                matrix.preRotate(angle);
            }
        }

        np = readTransform(s, "translate");
        if (np != null) {
            float tx = np.get(0);
            float ty = 0;
            if (np.size() > 1) {
                ty = np.get(1);
            }
            if (matrix == null) {
                matrix = new Matrix();
            }
            matrix.postTranslate(tx, ty);
        }

        return matrix;
    }

    /**
     * This is where the hard-to-parse paths are handled.
     * Uppercase rules are absolute positions, lowercase are relative.
     * Types of path rules:
     * <p/>
     * <ol>
     * <li>M/m - (x y)+ - Move to (without drawing)
     * <li>Z/z - (no params) - Close path (back to starting point)
     * <li>L/l - (x y)+ - Line to
     * <li>H/h - x+ - Horizontal ine to
     * <li>V/v - y+ - Vertical line to
     * <li>C/c - (mX1 y1 x2 y2 x y)+ - Cubic bezier to
     * <li>S/s - (x2 y2 x y)+ - Smooth cubic bezier to (shorthand that assumes the x2, y2 from previous C/S is the mX1, y1 of this bezier)
     * <li>Q/q - (mX1 y1 x y)+ - Quadratic bezier to
     * <li>T/t - (x y)+ - Smooth quadratic bezier to (assumes previous control point is "reflection" of last one w.r.t. to current point)
     * </ol>
     * <p/>
     * Numbers are separate by whitespace, comma or nothing at all (!) if they are self-delimiting, (ie. begin with a - sign)
     *
     * @param s the path text from the XML
     */
    @NonNull
    private static Path doPath(@NonNull String s) {
        int n = s.length();
        SvgParserHelper ph = new SvgParserHelper(s, 0);
        ph.skipWhitespace();
        Path p = new Path();
        float lastX = 0;
        float lastY = 0;
        float lastX1 = 0;
        float lastY1 = 0;
        float subPathStartX = 0;
        float subPathStartY = 0;
        char prevCmd = 0;
        while (ph.pos < n) {
            char cmd = s.charAt(ph.pos);
            switch (cmd) {
                case '.':
                case '-':
                case '+':
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    if (prevCmd == 'm' || prevCmd == 'M') {
                        cmd = (char) (((int) prevCmd) - 1);
                        break;
                    } else if (("lhvcsqta").indexOf(Character.toLowerCase(prevCmd)) >= 0) {
                        cmd = prevCmd;
                        break;
                    }
                default: {
                    ph.advance();
                    prevCmd = cmd;
                }
            }

            boolean wasCurve = false;
            switch (cmd) {
                case 'Z':
                case 'z': {
                    // Close path
                    p.close();
                    p.moveTo(subPathStartX, subPathStartY);
                    lastX = subPathStartX;
                    lastY = subPathStartY;
                    lastX1 = subPathStartX;
                    lastY1 = subPathStartY;
                    wasCurve = true;
                    break;
                }
                case 'M':
                case 'm': {
                    // Move
                    float x = ph.nextFloat();
                    float y = ph.nextFloat();
                    if (Character.isLowerCase(cmd)) {
                        subPathStartX += x;
                        subPathStartY += y;
                        p.rMoveTo(x, y);
                        lastX += x;
                        lastY += y;
                    } else {
                        subPathStartX = x;
                        subPathStartY = y;
                        p.moveTo(x, y);
                        lastX = x;
                        lastY = y;
                    }
                    break;
                }
                case 'L':
                case 'l': {
                    // Line
                    float x = ph.nextFloat();
                    float y = ph.nextFloat();
                    if (Character.isLowerCase(cmd)) {
                        p.rLineTo(x, y);
                        lastX += x;
                        lastY += y;
                    } else {
                        p.lineTo(x, y);
                        lastX = x;
                        lastY = y;
                    }
                    break;
                }
                case 'H':
                case 'h': {
                    // Horizontal line
                    float x = ph.nextFloat();
                    if (Character.isLowerCase(cmd)) {
                        p.rLineTo(x, 0);
                        lastX += x;
                    } else {
                        p.lineTo(x, lastY);
                        lastX = x;
                    }
                    break;
                }
                case 'V':
                case 'v': {
                    // Vertical line
                    float y = ph.nextFloat();
                    if (Character.isLowerCase(cmd)) {
                        p.rLineTo(0, y);
                        lastY += y;
                    } else {
                        p.lineTo(lastX, y);
                        lastY = y;
                    }
                    break;
                }
                case 'C':
                case 'c': {
                    // Cubic Bézier (six parameters)
                    wasCurve = true;
                    float x1 = ph.nextFloat();
                    float y1 = ph.nextFloat();
                    float x2 = ph.nextFloat();
                    float y2 = ph.nextFloat();
                    float x = ph.nextFloat();
                    float y = ph.nextFloat();
                    if (Character.isLowerCase(cmd)) {
                        // Relative coordinates
                        x1 += lastX;
                        x2 += lastX;
                        x += lastX;
                        y1 += lastY;
                        y2 += lastY;
                        y += lastY;
                    }
                    p.cubicTo(x1, y1, x2, y2, x, y);
                    lastX1 = x2;
                    lastY1 = y2;
                    lastX = x;
                    lastY = y;
                    break;
                }
                case 'S':
                case 's': {
                    // Shorthand cubic Bézier (four parameters)
                    wasCurve = true;
                    float x2 = ph.nextFloat();
                    float y2 = ph.nextFloat();
                    float x = ph.nextFloat();
                    float y = ph.nextFloat();
                    if (Character.isLowerCase(cmd)) {
                        // Relative coordinates
                        x2 += lastX;
                        x += lastX;
                        y2 += lastY;
                        y += lastY;
                    }
                    float x1 = 2 * lastX - lastX1;
                    float y1 = 2 * lastY - lastY1;
                    p.cubicTo(x1, y1, x2, y2, x, y);
                    lastX1 = x2;
                    lastY1 = y2;
                    lastX = x;
                    lastY = y;
                    break;
                }
                case 'Q':
                case 'q': {
                    // Quadratic Bézier (four parameters)
                    wasCurve = true;
                    float x1 = ph.nextFloat();
                    float y1 = ph.nextFloat();
                    float x = ph.nextFloat();
                    float y = ph.nextFloat();
                    if (Character.isLowerCase(cmd)) {
                        // Relative coordinates
                        x1 += lastX;
                        x += lastX;
                        y1 += lastY;
                        y += lastY;
                    }
                    p.quadTo(x1, y1, x, y);
                    lastX1 = x1;
                    lastY1 = y1;
                    lastX = x;
                    lastY = y;
                    break;
                }
                case 'T':
                case 't': {
                    // Shorthand quadratic Bézier (two parameters)
                    wasCurve = true;
                    float x = ph.nextFloat();
                    float y = ph.nextFloat();
                    if (Character.isLowerCase(cmd)) {
                        // Relative coordinates
                        x += lastX;
                        y += lastY;
                    }
                    float x1 = 2 * lastX - lastX1;
                    float y1 = 2 * lastY - lastY1;
                    p.quadTo(x1, y1, x, y);
                    lastX1 = x1;
                    lastY1 = y1;
                    lastX = x;
                    lastY = y;
                    break;
                }
                case 'A':
                case 'a': {
                    // Elliptical arc
                    float rx = ph.nextFloat();
                    float ry = ph.nextFloat();
                    float theta = ph.nextFloat();
                    int largeArc = ph.nextFlag();
                    int sweepArc = ph.nextFlag();
                    float x = ph.nextFloat();
                    float y = ph.nextFloat();
                    if (Character.isLowerCase(cmd)) {
                        x += lastX;
                        y += lastY;
                    }
                    drawArc(p, lastX, lastY, x, y, rx, ry, theta, largeArc, sweepArc);
                    lastX = x;
                    lastY = y;
                    break;
                }
            }
            if (!wasCurve) {
                lastX1 = lastX;
                lastY1 = lastY;
            }
            ph.skipWhitespace();
        }
        return p;
    }

    private static float angle(float y1, float x1, float y2, float x2) {
        return (float) Math.toDegrees(Math.atan2(y1, x1) - Math.atan2(y2, x2)) % 360;
    }

    private static final RectF arcRectf = new RectF();
    private static final Matrix arcMatrix = new Matrix();
    private static final Matrix arcMatrix2 = new Matrix();

    private static void drawArc(Path p, float lastX, float lastY, float x, float y,
                                float rx, float ry, float theta, int largeArc, int sweepArc) {
        //Log.d("drawArc", "from (" + lastX + "," + lastY + ") to (" + x + ","+ y + ") r=(" + rx + "," + ry + ") theta=" + theta + " flags="+ largeArc + "," + sweepArc);

        // http://www.w3.org/TR/SVG/implnote.html#ArcImplementationNotes

        if (rx == 0 || ry == 0) {
            p.lineTo(x, y);
            return;
        }

        if (x == lastX && y == lastY) {
            return; // nothing to draw
        }

        rx = Math.abs(rx);
        ry = Math.abs(ry);

        final float thrad = theta * (float) Math.PI / 180;
        final float st = (float) Math.sin(thrad);
        final float ct = (float) Math.cos(thrad);

        final float xc = (lastX - x) / 2;
        final float yc = (lastY - y) / 2;
        final float x1t = ct * xc + st * yc;
        final float y1t = -st * xc + ct * yc;

        final float x1ts = x1t * x1t;
        final float y1ts = y1t * y1t;
        float rxs = rx * rx;
        float rys = ry * ry;

        float lambda = (x1ts / rxs + y1ts / rys) * 1.001f; // add 0.1% to be sure that no out of range occurs due to limited precision
        if (lambda > 1) {
            float lambdasr = (float) Math.sqrt(lambda);
            rx *= lambdasr;
            ry *= lambdasr;
            rxs = rx * rx;
            rys = ry * ry;
        }

        final float R = (float) Math.sqrt((rxs * rys - rxs * y1ts - rys * x1ts) / (rxs * y1ts + rys * x1ts))
                * ((largeArc == sweepArc) ? -1 : 1);
        final float cxt = R * rx * y1t / ry;
        final float cyt = -R * ry * x1t / rx;
        final float cx = ct * cxt - st * cyt + (lastX + x) / 2;
        final float cy = st * cxt + ct * cyt + (lastY + y) / 2;

        final float th1 = angle(1, 0, (x1t - cxt) / rx, (y1t - cyt) / ry);
        float dth = angle((x1t - cxt) / rx, (y1t - cyt) / ry, (-x1t - cxt) / rx, (-y1t - cyt) / ry);

        if (sweepArc == 0 && dth > 0) {
            dth -= 360;
        } else if (sweepArc != 0 && dth < 0) {
            dth += 360;
        }

        // draw
        if ((theta % 360) == 0) {
            // no rotate and translate need
            arcRectf.set(cx - rx, cy - ry, cx + rx, cy + ry);
            p.arcTo(arcRectf, th1, dth);
        } else {
            // this is the hard and slow part :-)
            arcRectf.set(-rx, -ry, rx, ry);

            arcMatrix.reset();
            arcMatrix.postRotate(theta);
            arcMatrix.postTranslate(cx, cy);
            arcMatrix.invert(arcMatrix2);

            p.transform(arcMatrix2);
            p.arcTo(arcRectf, th1, dth);
            p.transform(arcMatrix);
        }
    }

    private static ArrayList<Float> getNumberParseAttr(String name,
                                                       Attributes attributes) {
        int n = attributes.getLength();
        for (int i = 0; i < n; i++) {
            if (attributes.getLocalName(i).equals(name)) {
                return parseNumbers(attributes.getValue(i));
            }
        }
        return null;
    }

    private static String getStringAttr(String name, Attributes attributes) {
        int n = attributes.getLength();
        for (int i = 0; i < n; i++) {
            if (attributes.getLocalName(i).equals(name)) {
                return attributes.getValue(i);
            }
        }
        return null;
    }

    private static Float getFloatAttr(String name, Attributes attributes) {
        return getFloatAttr(name, attributes, null);
    }

    private static Float getFloatAttr(String name, Attributes attributes, Float defaultValue) {
        String value = getStringAttr(name, attributes);
        return parseFloat(value, defaultValue);
    }

    private static Float parseFloat(String value, Float defaultValue) {
        if (value == null) {
            return defaultValue;
        } else {
            float scaleFactor = 1f;
            Unit unit = Unit.matches(value);
            if (unit != null) {
                value = value.substring(0, value.length() - unit.mAbbreviation.length());
            }
            float valueF = Float.parseFloat(value);
            if (unit != null) {
                switch (unit) {
                    case PT:
                        valueF = valueF + 0.5f;
                        break;
                    case PERCENT:
                        valueF = valueF / 100f;
                        break;
                }
                checkAssumedUnits(unit.mAbbreviation);
                scaleFactor = unit.mScaleFactor;
            }
            return valueF * scaleFactor;
        }
    }

    private void onSvgStart(@NonNull Canvas canvas,
                            @Nullable RectF bounds) {
        if (mOnElementListener != null) {
            mOnElementListener.onSvgStart(canvas, bounds);
        }
    }

    private void onSvgEnd(@NonNull Canvas canvas,
                          @Nullable RectF bounds) {
        if (mOnElementListener != null) {
            mOnElementListener.onSvgEnd(canvas, bounds);
        }
    }

    private <T> T onSvgElement(@Nullable String id,
                               @NonNull T element,
                               @Nullable RectF elementBounds,
                               @NonNull Canvas canvas,
                               @Nullable RectF canvasBounds,
                               @Nullable Paint paint) {
        if (mOnElementListener != null) {
            return mOnElementListener.onSvgElement(
                    id, element, elementBounds, canvas, canvasBounds, paint);
        }
        return element;
    }

    private <T> void onSvgElementDrawn(@Nullable String id,
                                       @NonNull T element,
                                       @NonNull Canvas canvas,
                                       @Nullable Paint paint) {
        if (mOnElementListener != null) {
            mOnElementListener.onSvgElementDrawn(id, element, canvas, paint);
        }
    }

//    private static class Gradient {
//
//        private String id;
//        private String xlink;
//        private boolean isLinear;
//        private float x1, y1, x2, y2;
//        private float x, y, radius;
//        private ArrayList<Float> positions = new ArrayList<>();
//        private ArrayList<Integer> colors = new ArrayList<>();
//        private Matrix matrix = null;
//
//        public Shader shader = null;
//        public boolean boundingBox = false;
//        public TileMode tileMode;
//
//        public void inherit(Gradient parent) {
//            Gradient child = this;
//            child.xlink = parent.id;
//            child.positions = parent.positions;
//            child.colors = parent.colors;
//            if (child.matrix == null) {
//                child.matrix = parent.matrix;
//            } else if (parent.matrix != null) {
//                Matrix m = new Matrix(parent.matrix);
//                m.preConcat(child.matrix);
//                child.matrix = m;
//            }
//        }
//    }

    private static class StyleSet {
        HashMap<String, String> styleMap = new HashMap<>();

        private StyleSet(String string) {
            String[] styles = string.split(";");
            for (String s : styles) {
                String[] style = s.split(":");
                if (style.length == 2) {
                    styleMap.put(style[0], style[1]);
                }
            }
        }

        public String getStyle(String name) {
            return styleMap.get(name);
        }
    }

    private static class Properties {

        StyleSet mStyles = null;
        Attributes mAttrs;

        private Properties(Attributes attrs) {
            mAttrs = attrs;
            String styleAttr = getStringAttr("style", attrs);
            if (styleAttr != null) {
                mStyles = new StyleSet(styleAttr);
            }
        }

        public String getAttr(String name) {
            String v = null;
            if (mStyles != null) {
                v = mStyles.getStyle(name);
            }
            if (v == null) {
                v = getStringAttr(name, mAttrs);
            }
            return v;
        }

        public String getString(String name) {
            return getAttr(name);
        }

        private Integer rgb(int r, int g, int b) {
            return ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
        }

        private int parseNum(String v) throws NumberFormatException {
            if (v.endsWith("%")) {
                v = v.substring(0, v.length() - 1);
                return Math.round(Float.parseFloat(v) / 100 * 255);
            }
            return Integer.parseInt(v);
        }

        public Integer getColor(String name) {
            String v = getAttr(name);
            if (v == null) {
                return null;
            } else if (v.startsWith("#")) {
                try {
                    int c = Integer.parseInt(v.substring(1), 16);
                    if (v.length() == 4) {
                        // short form color, i.e. #FFF
                        c = hex3Tohex6(c);
                    }
                    return c;
                } catch (NumberFormatException nfe) {
                    return null;
                }
            } else if (v.startsWith("rgb(") && v.endsWith(")")) {
                String[] values = v.substring(4, v.length() - 1).split(",");
                try {
                    return rgb(parseNum(values[0]), parseNum(values[1]), parseNum(values[2]));
                } catch (NumberFormatException nfe) {
                    return null;
                } catch (ArrayIndexOutOfBoundsException e) {
                    return null;
                }
            } else {
                return SvgColors.mapColor(v);
            }
        }

        // convert 0xRGB into 0xRRGGBB
        private int hex3Tohex6(int x) {
            return (x & 0xF00) << 8 | (x & 0xF00) << 12 |
                    (x & 0xF0) << 4 | (x & 0xF0) << 8 |
                    (x & 0xF) << 4 | (x & 0xF);
        }

        public Float getFloat(String name, float defaultValue) {
            Float v = getFloat(name);
            if (v == null) {
                return defaultValue;
            } else {
                return v;
            }
        }

        public Float getFloat(String name) {
            String v = getAttr(name);
            if (v == null) {
                return null;
            } else {
                try {
                    return Float.parseFloat(v);
                } catch (NumberFormatException nfe) {
                    return null;
                }
            }
        }
    }

    public static class SvgHandler extends DefaultHandler {

        private final SvgCompact svgCompact;
        private Picture picture;
        private Canvas canvas;
        private Paint strokePaint;
        private boolean strokeSet = false;
        private Stack<Paint> strokePaintStack = new Stack<>();
        private Stack<Boolean> strokeSetStack = new Stack<>();

        private Paint fillPaint;
        private boolean fillSet = false;
        private Stack<Paint> fillPaintStack = new Stack<>();
        private Stack<Boolean> fillSetStack = new Stack<>();

        // Scratch rect (so we aren't constantly making new ones)
        private RectF line = new RectF();
        private RectF rect = new RectF();
        private RectF bounds = null;
        private RectF limits = new RectF(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);

        private Stack<Boolean> transformStack = new Stack<>();
        private Stack<Matrix> matrixStack = new Stack<>();

        private HashMap<String, Gradient> gradientMap = new HashMap<>();
        private Gradient gradient = null;

        private final Stack<SvgText> textStack = new Stack<>();
        private final Stack<SvgGroup> groupStack = new Stack<>();

        private HashMap<String, String> defs = new HashMap<>();
        private boolean readingDefs = false;
        private Stack<String> readIgnoreStack = new Stack<>();

        private SvgHandler(SvgCompact svgCompact) {
            this.svgCompact = svgCompact;
        }

        private void onSvgStart() {
            svgCompact.onSvgStart(canvas, bounds);
        }

        private void onSvgEnd() {
            svgCompact.onSvgEnd(canvas, bounds);
        }

        private <T> T onSvgElement(@Nullable String id,
                                   @NonNull T element,
                                   @Nullable RectF elementBounds,
                                   @Nullable Paint paint) {
            return svgCompact.onSvgElement(id, element, elementBounds, canvas, bounds, paint);
        }

        private <T> void onSvgElementDrawn(@Nullable String id,
                                           @NonNull T element,
                                           @Nullable Paint paint) {
            svgCompact.onSvgElementDrawn(id, element, canvas, paint);
        }

        public void read(InputStream inputStream) {
            picture = new Picture();
            try {
                long start = System.currentTimeMillis();
                if (inputStream.markSupported()) {
                    inputStream.mark(4);
                    byte[] magic = new byte[2];
                    int r = inputStream.read(magic, 0, 2);
                    int magicInt = (magic[0] + (((int) magic[1]) << 8)) & 0xffff;
                    inputStream.reset();
                    if (r == 2 && magicInt == GZIPInputStream.GZIP_MAGIC) {
                        if (LOG_LEVEL >= LOG_LEVEL_INFO) {
                            Log.d(TAG, "SVG is gzipped");
                        }
                        inputStream = new GZIPInputStream(inputStream);
                    }
                }
                SAXParserFactory spf = SAXParserFactory.newInstance();
                SAXParser sp = spf.newSAXParser();
                XMLReader xr = sp.getXMLReader();
                xr.setContentHandler(this);
                xr.parse(new InputSource(inputStream));
                if (textDynamic != null) {
                    textDynamic.clear();
                    textDynamic = null;
                }
                if (LOG_LEVEL >= LOG_LEVEL_INFO) {
                    Log.v(TAG, "Parsing complete inputStream " + (System.currentTimeMillis() - start) + " ms.");
                }
            } catch (IOException | SAXException | ParserConfigurationException e) {
                Log.e(TAG, "Failed parsing SVG", e);
                throw new SvgParseException(e);
            }
        }

        @Override
        public void startDocument() throws SAXException {
            // Set up prior to parsing a doc
            strokePaint = new Paint();
            strokePaint.setAntiAlias(true);
            strokePaint.setStyle(Paint.Style.STROKE);

            fillPaint = new Paint();
            fillPaint.setAntiAlias(true);
            fillPaint.setStyle(Paint.Style.FILL);

            matrixStack.push(new Matrix());
        }

        @Override
        public void endDocument() throws SAXException {
            // Clean up after parsing a doc
            defs.clear();
            matrixStack.clear();
        }

        private final Matrix gradMatrix = new Matrix();

        private boolean doFill(Properties atts, RectF boundingBox) {
            if ("none".equals(atts.getString("display"))) {
                return false;
            }
            String fillString = atts.getString("fill");
            if (fillString != null) {
                if (fillString.startsWith("url(#")) {
                    // It's a gradient fill, look it up in our map
                    String id = fillString.substring("url(#".length(), fillString.length() - 1);
                    Gradient g = gradientMap.get(id);
                    Shader shader = null;
                    if (g != null) {
                        shader = g.shader;
                    }
                    if (shader != null) {
                        //Util.debug("Found shader!");
                        fillPaint.setShader(shader);
                        if (boundingBox != null) {
                            gradMatrix.set(g.matrix);
                            if (g.boundingBox) {
                                //Log.d(TAG, "gradient is bounding box");
                                gradMatrix.preTranslate(boundingBox.left, boundingBox.top);
                                gradMatrix.preScale(boundingBox.width(), boundingBox.height());
                            }
                            shader.setLocalMatrix(gradMatrix);
                        }
                    } else {
                        //Log.d(TAG, "Didn't find shader, using black: " + id);
                        fillPaint.setShader(null);
                        doColor(atts, Color.BLACK, true, fillPaint);
                    }
                    return true;
                } else if (fillString.equalsIgnoreCase("none")) {
                    fillPaint.setShader(null);
                    fillPaint.setColor(Color.TRANSPARENT);
                    // optimization: return false if transparent
                    return false;
                } else {
                    fillPaint.setShader(null);
                    Integer color = atts.getColor("fill");
                    if (color != null) {
                        color = getMappedColor(color);
                        doColor(atts, color, true, fillPaint);
                    } else {
                        if (LOG_LEVEL >= LOG_LEVEL_WARN) {
                            Log.w(TAG, "Unrecognized fill color, using black: " + fillString);
                        }
                        doColor(atts, Color.BLACK, true, fillPaint);
                    }
                    return true;
                }
            } else {
                if (fillSet) {
                    // If fill is set, inherit from parent
                    // optimization: return false if transparent
                    return fillPaint.getColor() != Color.TRANSPARENT;
                } else {
                    // Default is black fill
                    fillPaint.setShader(null);
                    fillPaint.setColor(Color.BLACK);
                    return true;
                }
            }
        }

        private boolean doText(Attributes atts, Properties props, Paint paint) {
            if ("none".equals(atts.getValue("display"))) {
                return false;
            }
            Float fontSize = getFloatAttr("font-size", atts);
            if (fontSize == null) {
                fontSize = parseFloat(props.getString("font-size"), null);
            }
            if (fontSize != null) {
                paint.setTextSize(fontSize);
            }
            Typeface typeface = setTypeface(atts, props, svgCompact.getAssetManager(), paint.getTypeface());
            if (typeface != null) {
                paint.setTypeface(typeface);
            }
            Align align = getTextAlign(atts);
            if (align != null) {
                paint.setTextAlign(getTextAlign(atts));
            }
            return true;
        }

        private boolean doStroke(Properties atts, RectF boundingBox) {
            if ("none".equals(atts.getString("display"))) {
                return false;
            }
            String strokeString = atts.getString("stroke");
            if (strokeString != null) {
                if (strokeString.equalsIgnoreCase("none")) {
                    strokePaint.setShader(null);
                    strokePaint.setColor(Color.TRANSPARENT);
                    // optimization: return false if transparent
                    return false;
                }
                // Check for other stroke attributes
                Float width = atts.getFloat("stroke-width");
                // Set defaults

                if (width != null) {
                    strokePaint.setStrokeWidth(width);
                }

                String dashArray = atts.getString("stroke-dasharray");
                if (dashArray != null && !dashArray.equalsIgnoreCase("none")) {
                    String[] splitDashArray = dashArray.split(", ?");
                    float[] intervals = new float[splitDashArray.length];
                    for (int i = 0; i < splitDashArray.length; i++) {
                        intervals[i] = Float.parseFloat(splitDashArray[i]);
                    }
                    strokePaint.setPathEffect(new DashPathEffect(intervals, 0));
                }

                String linecap = atts.getString("stroke-linecap");
                if ("round".equals(linecap)) {
                    strokePaint.setStrokeCap(Paint.Cap.ROUND);
                } else if ("square".equals(linecap)) {
                    strokePaint.setStrokeCap(Paint.Cap.SQUARE);
                } else if ("butt".equals(linecap)) {
                    strokePaint.setStrokeCap(Paint.Cap.BUTT);
                }
                String linejoin = atts.getString("stroke-linejoin");
                if ("miter".equals(linejoin)) {
                    strokePaint.setStrokeJoin(Paint.Join.MITER);
                } else if ("round".equals(linejoin)) {
                    strokePaint.setStrokeJoin(Paint.Join.ROUND);
                } else if ("bevel".equals(linejoin)) {
                    strokePaint.setStrokeJoin(Paint.Join.BEVEL);
                }

                // Display the stroke
                strokePaint.setStyle(Paint.Style.STROKE);

                if (strokeString.startsWith("url(#")) {
                    // It's a gradient stroke, look it up in our map
                    String id = strokeString.substring("url(#".length(), strokeString.length() - 1);
                    Gradient g = gradientMap.get(id);
                    Shader shader = null;
                    if (g != null) {
                        shader = g.shader;
                    }
                    if (shader != null) {
                        //Util.debug("Found shader!");
                        strokePaint.setShader(shader);
                        if (boundingBox != null) {
                            gradMatrix.set(g.matrix);
                            if (g.boundingBox) {
                                //Log.d(TAG, "gradient is bounding box");
                                gradMatrix.preTranslate(boundingBox.left, boundingBox.top);
                                gradMatrix.preScale(boundingBox.width(), boundingBox.height());
                            }
                            shader.setLocalMatrix(gradMatrix);
                        }
                        return true;
                    } else {
                        if (LOG_LEVEL >= LOG_LEVEL_WARN) {
                            Log.w(TAG, "Didn't find shader, using black: " + id);
                        }
                        strokePaint.setShader(null);
                        doColor(atts, Color.BLACK, true, strokePaint);
                        return true;
                    }
                } else {
                    Integer color = atts.getColor("stroke");
                    if (color != null) {
                        color = getMappedColor(color);
                        doColor(atts, color, false, strokePaint);
                    } else {
                        if (LOG_LEVEL >= LOG_LEVEL_WARN) {
                            Log.w(TAG, "Unrecognized stroke color, using black: " + strokeString);
                        }
                        doColor(atts, Color.BLACK, true, strokePaint);
                    }
                    return true;
                }
            } else {
                if (strokeSet) {
                    // If stroke is set, inherit from parent
                    // optimization: return false if transparent
                    return strokePaint.getColor() != Color.TRANSPARENT;
                } else {
                    // Default is no stroke
                    strokePaint.setShader(null);
                    strokePaint.setColor(Color.TRANSPARENT);
                    // optimization: return false if transparent
                    return false;
                }
            }
        }

        private Gradient doGradient(boolean isLinear, Attributes atts) {
            Gradient gradient = new Gradient();
            gradient.id = getStringAttr("id", atts);
            gradient.isLinear = isLinear;
            if (isLinear) {
                gradient.x1 = getFloatAttr("x1", atts, 0f);
                gradient.x2 = getFloatAttr("x2", atts, 1f);
                gradient.y1 = getFloatAttr("y1", atts, 0f);
                gradient.y2 = getFloatAttr("y2", atts, 0f);
            } else {
                gradient.x = getFloatAttr("cx", atts, 0f);
                gradient.y = getFloatAttr("cy", atts, 0f);
                gradient.radius = getFloatAttr("r", atts, 0f);
            }
            String transform = getStringAttr("gradientTransform", atts);
            if (transform != null) {
                gradient.matrix = parseTransform(transform);
            }
            String spreadMethod = getStringAttr("spreadMethod", atts);
            if (spreadMethod == null) {
                spreadMethod = "pad";
            }

            gradient.tileMode = (spreadMethod.equals("reflect")) ? TileMode.MIRROR :
                    (spreadMethod.equals("repeat")) ? TileMode.REPEAT :
                            TileMode.CLAMP;

            String unit = getStringAttr("gradientUnits", atts);
            if (unit == null) {
                unit = "objectBoundingBox";
            }
            gradient.boundingBox = !unit.equals("userSpaceOnUse");

            String xlink = getStringAttr("href", atts);
            if (xlink != null) {
                if (xlink.startsWith("#")) {
                    xlink = xlink.substring(1);
                }
                gradient.xlink = xlink;
            }
            return gradient;
        }

        private void finishGradients() {
            for (Gradient gradient : gradientMap.values()) {
                if (gradient.xlink != null) {
                    Gradient parent = gradientMap.get(gradient.xlink);
                    if (parent != null) {
                        gradient.inherit(parent);
                    }
                }
                int[] colors = new int[gradient.colors.size()];
                for (int i = 0; i < colors.length; i++) {
                    colors[i] = gradient.colors.get(i);
                }
                float[] positions = new float[gradient.positions.size()];
                for (int i = 0; i < positions.length; i++) {
                    positions[i] = gradient.positions.get(i);
                }
                if (colors.length == 0) {
                    if (LOG_LEVEL >= LOG_LEVEL_WARN) {
                        Log.w(TAG, "Failed to parse gradient for id " + gradient.id);
                    }
                }
                if (gradient.isLinear) {
                    gradient.shader = new LinearGradient(gradient.x1, gradient.y1, gradient.x2, gradient.y2, colors, positions, gradient.tileMode);
                } else {
                    gradient.shader = new RadialGradient(gradient.x, gradient.y, gradient.radius, colors, positions, gradient.tileMode);
                }
            }
        }

        private void doColor(Properties atts, Integer color, boolean fillMode, Paint paint) {
            int c = (0xFFFFFF & color) | 0xFF000000;
            paint.setShader(null);
            paint.setColor(c);
            Float opacity = atts.getFloat("opacity");
            Float opacity2 = atts.getFloat(fillMode ? "fill-opacity" : "stroke-opacity");
            if (opacity == null) {
                opacity = opacity2;
            } else if (opacity2 != null) {
                opacity *= opacity2;
            }
            if (opacity == null) {
                paint.setAlpha(255);
            } else {
                paint.setAlpha((int) (255 * opacity));
            }
        }

        private boolean hidden = false;
        private int hiddenLevel = 0;
        private boolean boundsMode = false;

        private void doLimits(float x, float y) {
            if (x < limits.left) {
                limits.left = x;
            }
            if (x > limits.right) {
                limits.right = x;
            }
            if (y < limits.top) {
                limits.top = y;
            }
            if (y > limits.bottom) {
                limits.bottom = y;
            }
        }

        final private RectF limitRect = new RectF();

        private void doLimits(RectF box, Paint paint) {
            Matrix m = matrixStack.peek();
            m.mapRect(limitRect, box);
            float width2 = (paint == null) ? 0 : strokePaint.getStrokeWidth() / 2;
            doLimits(limitRect.left - width2, limitRect.top - width2);
            doLimits(limitRect.right + width2, limitRect.bottom + width2);
        }

        private void doLimits(RectF box) {
            doLimits(box, null);
        }

        private void pushTransform(Attributes atts) {
            final String transform = getStringAttr("transform", atts);
            boolean pushed = transform != null;
            transformStack.push(pushed);
            if (pushed) {
                canvas.save();
                final Matrix matrix = parseTransform(transform);
                if (matrix != null) {
                    canvas.concat(matrix);
                    matrix.postConcat(matrixStack.peek());
                    matrixStack.push(matrix);
                }
            }
        }

        private void popTransform() {
            if (transformStack.pop()) {
                canvas.restore();
                matrixStack.pop();
            }
        }

        @Override
        public void startElement(String namespaceURI, String localName, String qName, Attributes atts) throws SAXException {
            if (!readIgnoreStack.empty()) {
                // Ignore
                return;
            }
            String id = getStringAttr("id", atts);

            // Reset paint opacity
            strokePaint.setAlpha(255);
            fillPaint.setAlpha(255);
            // Ignore everything but rectangles in bounds mode
            if (boundsMode) {
                if (localName.equals("rect")) {
                    Float x = getFloatAttr("x", atts);
                    if (x == null) {
                        x = 0f;
                    }
                    Float y = getFloatAttr("y", atts);
                    if (y == null) {
                        y = 0f;
                    }
                    Float width = getFloatAttr("width", atts);
                    Float height = getFloatAttr("height", atts);
                    bounds = new RectF(x, y, x + width, y + height);
                }
                return;
            }

            if (!hidden && localName.equals("use")) {
                localName = "path";
            }

            if (localName.equals("svg")) {
                float x = 0, y = 0, width = -1, height = -1;
                String viewBox = getStringAttr("viewBox", atts);
                if (viewBox != null) {
                    // Prefer viewBox
                    String[] coords = viewBox.split(" ");
                    if (coords.length == 4) {
                        x = parseFloat(coords[0], 0f);
                        y = parseFloat(coords[1], 0f);
                        width = parseFloat(coords[2], -1f);
                        height = parseFloat(coords[3], -1f);
                    }
                } else {
                    Float svgWidth = getFloatAttr("width", atts);
                    Float svgHeight = getFloatAttr("height", atts);
                    if (svgWidth != null && svgHeight != null) {
                        width = (int) Math.ceil(svgWidth);
                        height = (int) Math.ceil(svgHeight);
                    }
                }
                if (width < 0 || height < 0) {
                    width = 100;
                    height = 100;
                    if (LOG_LEVEL >= LOG_LEVEL_WARN) {
                        Log.w(TAG, "Element '" + localName + "' does not provide its dimensions; using " + width + "x" + height);
                    }
                }
                bounds = new RectF(x, y, x + width, y + height);
                //Log.d(TAG, "svg boundaries: " + mBounds);
                canvas = picture.beginRecording(
                        (int) Math.ceil(bounds.width()),
                        (int) Math.ceil(bounds.height()));
                canvas.translate(-bounds.left, -bounds.top);
                //Log.d(TAG, "canvas size: " + mCanvas.getWidth() + "x" + mCanvas.getHeight());
                onSvgStart();
            } else if (localName.equals("defs")) {
                readingDefs = true;
            } else if (localName.equals("linearGradient")) {
                gradient = doGradient(true, atts);
            } else if (localName.equals("radialGradient")) {
                gradient = doGradient(false, atts);
            } else if (localName.equals("stop")) {
                if (gradient != null) {
                    Properties props = new Properties(atts);
                    float offset = props.getFloat("offset", 0);
                    Integer color = props.getColor("stop-color");
                    float alpha = props.getFloat("stop-opacity", 1);
                    int alphaInt = Math.round(255 * alpha);
                    gradient.positions.add(offset);

                    if (color == null) {
                        color = Color.TRANSPARENT; // to avoid null exception
                    } else {
                        color = getMappedColor(color);
                    }

                    color |= (alphaInt << 24);
                    gradient.colors.add(color);
                }
            } else if (localName.equals("g")) {
                Properties props = new Properties(atts);
                // Check to see if this is the "bounds" layer
                if ("bounds".equalsIgnoreCase(id)) {
                    boundsMode = true;
                }
                if (hidden) {
                    hiddenLevel++;
                }
                // Go in to hidden mode if display is "none"
                if ("none".equals(props.getString("display"))) {
                    if (!hidden) {
                        hidden = true;
                        hiddenLevel = 1;
                    }
                }

                // If the group has an applied opacity, start drawing in a new canvas
                Float opacity = getFloatAttr("opacity", atts);
                if (opacity == null) {
                    opacity = props.getFloat("opacity");
                }
                if (opacity != null && opacity < 1f) {
                    // FIXME Ideally, we should compute the bounds of the enclosed group, and create
                    //       the layer exactly to its size; see issue #6
                    // Apply inverse of matrix to correct for any transformations
                    // It's okay to use getMatrix() here as we may assume its a software layer
                    Matrix m = canvas.getMatrix();
                    m.invert(m);
                    RectF r = new RectF(0, 0, canvas.getWidth(), canvas.getHeight());
                    m.mapRect(r);
                    // Store the layer with the opacity value
                    canvas.saveLayerAlpha(r,
                            (int) (255 * opacity), Canvas.ALL_SAVE_FLAG);
                } else {
                    canvas.save();
                }

                pushTransform(atts);

                fillPaintStack.push(new Paint(fillPaint));
                strokePaintStack.push(new Paint(strokePaint));
                fillSetStack.push(fillSet);
                strokeSetStack.push(strokeSet);

                doFill(props, null);
                doStroke(props, null);

                fillSet |= (props.getString("fill") != null);
                strokeSet |= (props.getString("stroke") != null);

                SvgGroup group = new SvgGroup(id);
                groupStack.push(group);
                // FIXME compute bounds before drawing?
                onSvgElement(id, group, null, null);
            } else if (!hidden && localName.equals("rect")) {
                Float x = getFloatAttr("x", atts, 0f);
                Float y = getFloatAttr("y", atts, 0f);

                Float width = getFloatAttr("width", atts);
                Float height = getFloatAttr("height", atts);
                Float rx = getFloatAttr("rx", atts);
                Float ry = getFloatAttr("ry", atts);
                if (ry == null) {
                    ry = rx;
                }
                if (rx == null) {
                    rx = ry;
                }
                if (rx == null || rx < 0) {
                    rx = 0f;
                }
                if (ry == null || ry < 0) {
                    ry = 0f;
                }
                if (rx > width / 2) {
                    rx = width / 2;
                }
                if (ry > height / 2) {
                    ry = height / 2;
                }
                pushTransform(atts);
                Properties props = new Properties(atts);
                rect.set(x, y, x + width, y + height);
                if (doFill(props, rect)) {
                    rect = onSvgElement(id, rect, rect, fillPaint);
                    if (rect != null) {
                        canvas.drawRoundRect(rect, rx, ry, fillPaint);
                        onSvgElementDrawn(id, rect, fillPaint);
                    }
                    doLimits(rect);
                }
                if (doStroke(props, rect)) {
                    rect = onSvgElement(id, rect, rect, strokePaint);
                    if (rect != null) {
                        canvas.drawRoundRect(rect, rx, ry, strokePaint);
                        onSvgElementDrawn(id, rect, strokePaint);
                    }
                    doLimits(rect, strokePaint);
                }
                popTransform();
            } else if (!hidden && localName.equals("line")) {
                Float x1 = getFloatAttr("x1", atts);
                Float x2 = getFloatAttr("x2", atts);
                Float y1 = getFloatAttr("y1", atts);
                Float y2 = getFloatAttr("y2", atts);
                Properties props = new Properties(atts);
                if (doStroke(props, rect)) {
                    pushTransform(atts);

                    if (x1 == null) x1 = 0f;
                    if (x2 == null) x2 = 0f;
                    if (y1 == null) y1 = 0f;
                    if (y2 == null) y2 = 0f;

                    line.set(x1, y1, x2, y2);
                    rect.set(line);
                    line = onSvgElement(id, line, rect, strokePaint);
                    if (line != null) {
                        canvas.drawLine(line.left, line.top, line.right, line.bottom, strokePaint);
                        onSvgElementDrawn(id, line, strokePaint);
                    }
                    doLimits(rect, strokePaint);
                    popTransform();
                }
            } else if (!hidden && (localName.equals("circle") || localName.equals("ellipse"))) {
                Float centerX, centerY, radiusX, radiusY;

                centerX = getFloatAttr("cx", atts);
                centerY = getFloatAttr("cy", atts);
                if (localName.equals("ellipse")) {
                    radiusX = getFloatAttr("rx", atts);
                    radiusY = getFloatAttr("ry", atts);
                } else {
                    radiusX = radiusY = getFloatAttr("r", atts);
                }
                if (centerX != null && centerY != null && radiusX != null && radiusY != null) {
                    pushTransform(atts);
                    Properties props = new Properties(atts);
                    rect.set(centerX - radiusX, centerY - radiusY, centerX + radiusX, centerY + radiusY);
                    if (doFill(props, rect)) {
                        rect = onSvgElement(id, rect, rect, fillPaint);
                        if (rect != null) {
                            canvas.drawOval(rect, fillPaint);
                            onSvgElementDrawn(id, rect, fillPaint);
                        }
                        doLimits(rect);
                    }
                    if (doStroke(props, rect)) {
                        rect = onSvgElement(id, rect, rect, strokePaint);
                        if (rect != null) {
                            canvas.drawOval(rect, strokePaint);
                            onSvgElementDrawn(id, rect, strokePaint);
                        }
                        doLimits(rect, strokePaint);
                    }
                    popTransform();
                }
            } else if (!hidden && (localName.equals("polygon") || localName.equals("polyline"))) {
                ArrayList<Float> points = getNumberParseAttr("points", atts);
                if (points != null) {
                    Path p = new Path();
                    if (points.size() > 1) {
                        pushTransform(atts);
                        Properties props = new Properties(atts);
                        p.moveTo(points.get(0), points.get(1));
                        for (int i = 2; i < points.size(); i += 2) {
                            float x = points.get(i);
                            float y = points.get(i + 1);
                            p.lineTo(x, y);
                        }
                        // Don't close a polyline
                        if (localName.equals("polygon")) {
                            p.close();
                        }
                        p.computeBounds(rect, false);
                        if (doFill(props, rect)) {
                            p = onSvgElement(id, p, rect, fillPaint);
                            if (p != null) {
                                canvas.drawPath(p, fillPaint);
                                onSvgElementDrawn(id, p, fillPaint);
                            }
                            doLimits(rect);
                        }
                        if (doStroke(props, rect)) {
                            p = onSvgElement(id, p, rect, strokePaint);
                            if (p != null) {
                                canvas.drawPath(p, strokePaint);
                                onSvgElementDrawn(id, p, strokePaint);
                            }
                            doLimits(rect, strokePaint);
                        }
                        popTransform();
                    }
                }
            } else if (!hidden && localName.equals("path")) {
                String d = getStringAttr("d", atts);

                if (readingDefs) {
                    defs.put(id, getStringAttr("d", atts));
                    return;
                } else if (null == d) {
                    String href = getStringAttr("href", atts);
                    if (href != null && href.startsWith("#")) {
                        href = href.substring(1);
                    }
                    if (href != null && defs.containsKey(href)) {
                        d = defs.get(href);
                    }
                    if (null == d) {
                        return;
                    }
                }
                Path p = doPath(d);
                pushTransform(atts);
                Properties props = new Properties(atts);
                p.computeBounds(rect, false);
                if (doFill(props, rect)) {
                    p = onSvgElement(id, p, rect, fillPaint);
                    if (p != null) {
                        canvas.drawPath(p, fillPaint);
                        onSvgElementDrawn(id, p, fillPaint);
                    }
                    doLimits(rect);
                }
                if (doStroke(props, rect)) {
                    p = onSvgElement(id, p, rect, strokePaint);
                    if (p != null) {
                        canvas.drawPath(p, strokePaint);
                        onSvgElementDrawn(id, p, strokePaint);
                    }
                    doLimits(rect, strokePaint);
                }
                popTransform();
            } else if (!hidden && localName.equals("text")) {
                pushTransform(atts);
                textStack.push(new SvgText(atts, textStack.isEmpty() ? null : textStack.peek()));
            } else if (!hidden && localName.equals("tspan")) {
                textStack.push(new SvgText(atts, textStack.isEmpty() ? null : textStack.peek()));
            } else if (!hidden && localName.equals("clipPath")) {
                hide();
                if (LOG_LEVEL >= LOG_LEVEL_WARN) {
                    Log.w(TAG, "Unsupported SVG command: " + localName);
                }
            } else if (!hidden) {
                switch (localName) {
                    case "metadata":
                        // Ignore, including children
                        readIgnoreStack.push(localName);
                        break;
                    default:
                        if (LOG_LEVEL >= LOG_LEVEL_WARN) {
                            Log.w(TAG, "Unrecognized SVG command: " + localName);
                        }
                        break;
                }
            }
        }

        private Integer getMappedColor(Integer color) {
            String hexColor = String.format("#%06X", (0xFFFFFF & color));
            Log.v(TAG, "Color: "+ hexColor);
            if (hexColor.length() >= 7) {
                Integer mappedColor = svgCompact.colorMap.get(color);
                if (mappedColor != null) {
                    color = mappedColor;
                } else if (!svgCompact.colorMap.containsKey(color)) {
                    svgCompact.colorMap.put(color, color);
                }
            }
            return color;
        }

        private void hide() {
            if (!hidden) {
                hidden = true;
                hiddenLevel = 1;
            } else {
                hiddenLevel++;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (!textStack.isEmpty()) {
                textStack.peek().setText(ch, start, length);
            }
        }

        @Override
        public void endElement(String namespaceURI, String localName, String qName) throws SAXException {
            if (!readIgnoreStack.empty() && localName.equals(readIgnoreStack.peek())) {
                // Ignore
                readIgnoreStack.pop();
                return;
            }
            switch (localName) {
                case "svg":
                    onSvgEnd();
                    picture.endRecording();
                    break;
                case "text":
                case "tspan":
                    if (!textStack.isEmpty()) {
                        SvgText text = textStack.pop();
                        if (text != null) {
                            text.render(canvas);
                        }
                    }
                    if (localName.equals("text")) {
                        popTransform();
                    }
                    break;
                case "linearGradient":
                case "radialGradient":
                    if (gradient.id != null) {
                        gradientMap.put(gradient.id, gradient);
                    }
                    break;
                case "defs":
                    finishGradients();
                    readingDefs = false;
                    break;
                case "g":
                    SvgGroup group = groupStack.pop();
                    onSvgElementDrawn(group.id, group, null);

                    if (boundsMode) {
                        boundsMode = false;
                    }
                    // Break out of hidden mode
                    unhide();
                    // Clear gradient map
                    //gradientRefMap.clear();
                    popTransform();
                    fillPaint = fillPaintStack.pop();
                    fillSet = fillSetStack.pop();
                    strokePaint = strokePaintStack.pop();
                    strokeSet = strokeSetStack.pop();

                    // Restore the previous canvas
                    canvas.restore();
                    break;
                case "clipPath":
                    // Break out of hidden mode
                    unhide();
                    break;
            }
        }

        private void unhide() {
            if (hidden) {
                hiddenLevel--;
                if (hiddenLevel == 0) {
                    hidden = false;
                }
            }
        }


        public class SvgGroup {

            private final String id;

            public SvgGroup(String id) {
                this.id = id;
            }

        }


        /**
         * Holds text properties as these are only applied with the end tag is encountered.
         */
        public class SvgText {

            private final static int LEFT = 0;
            private final static int CENTER = 1;
            private final static int RIGHT = 2;

            private final static int BOTTOM = 0;
            private final static int MIDDLE = 1;
            private final static int TOP = 2;

            private final String id;
            private final float x, y;
            private float xOffset, yOffset;
            private final String[] xCoords;
            private TextPaint stroke = null, fill = null;
            private String text;
            private int hAlign = LEFT, vAlign = BOTTOM;
            private RectF bounds = new RectF();

            public SvgText(Attributes atts, SvgText parentText) {
                id = getStringAttr("id", atts);
                String xStr = getStringAttr("x", atts);
                if (xStr != null && (xStr.contains(",") || xStr.contains(" "))) {
                    // x is a comma- or space-separated list of coordinates; see:
                    // http://www.w3.org/TR/SVG/text.html#TSpanElementXAttribute
                    x = parentText != null ? parentText.x : 0f;
                    xCoords = xStr.split("[, ]");
                } else {
                    // x is a single coordinate
                    x = parseFloat(xStr, parentText != null ? parentText.x : 0f);
                    xCoords = parentText != null ? parentText.xCoords : null;
                }
                y = getFloatAttr("y", atts, parentText != null ? parentText.y : 0f);
                text = null;

                Properties props = new Properties(atts);
                if (doFill(props, null)) {
                    fill = new TextPaint(parentText != null && parentText.fill != null
                            ? parentText.fill
                            : fillPaint);
                    // Fix for https://code.google.com/p/android/issues/detail?id=39755
                    fill.setLinearText(true);
                    doText(atts, props, fill);
                }
                if (doStroke(props, null)) {
                    stroke = new TextPaint(parentText != null && parentText.stroke != null
                            ? parentText.stroke
                            : strokePaint);
                    // Fix for https://code.google.com/p/android/issues/detail?id=39755
                    stroke.setLinearText(true);
                    doText(atts, props, stroke);
                }
                // Horizontal alignment
                String halign = getStringAttr("text-align", atts);
                if (halign == null) {
                    halign = props.getString("text-align");
                }
                if (halign == null && parentText != null) {
                    hAlign = parentText.hAlign;
                } else {
                    if ("center".equals(halign)) {
                        hAlign = CENTER;
                    } else if ("right".equals(halign)) {
                        hAlign = RIGHT;
                    }
                }
                // Vertical alignment
                String valign = getStringAttr("alignment-baseline", atts);
                if (valign == null) {
                    valign = props.getString("alignment-baseline");
                }
                if (valign == null && parentText != null) {
                    vAlign = parentText.vAlign;
                } else {
                    if ("middle".equals(valign)) {
                        vAlign = MIDDLE;
                    } else if ("top".equals(valign)) {
                        vAlign = TOP;
                    }
                }
            }

            public void setText(char[] ch, int start, int len) {
                if (text == null) {
                    text = new String(ch, start, len);
                } else {
                    text += new String(ch, start, len);
                }
                if (textDynamic != null && textDynamic.containsKey(text)) {
                    text = textDynamic.get(text);
                }
            }

            public void render(Canvas canvas) {
                if (text == null) {
                    // Nothing to draw
                    return;
                }
                // Correct vertical alignment
                Rect bounds = new Rect();
                Paint paint = stroke == null ? fill : stroke;
                paint.getTextBounds(text, 0, text.length(), bounds);
                //Log.d(TAG, "Adjusting y=" + y + " for boundaries=" + bounds);
                switch (vAlign) {
                    case TOP:
                        yOffset = bounds.height();
                        break;
                    case MIDDLE:
                        yOffset = -bounds.centerY();
                        break;
                    case BOTTOM:
                        // Default; no correction needed
                        break;
                }
                float width = paint.measureText(text);
                // Correct horizontal alignment
                switch (hAlign) {
                    case LEFT:
                        // Default; no correction needed
                        break;
                    case CENTER:
                        xOffset = -width / 2f;
                        break;
                    case RIGHT:
                        xOffset = -width;
                }
                this.bounds.set(x, y, x + width, y + bounds.height());

                //Log.i(TAG, "Drawing: " + text + " " + x + "," + y);
                if (text != null) {
                    if (fill != null) {
                        drawText(canvas, this, true);
                    }
                    if (stroke != null) {
                        drawText(canvas, this, false);
                    }
                }
            }

            private void drawText(Canvas canvas, SvgText text, boolean fill) {
                TextPaint paint = fill ? text.fill : text.stroke;
                text = onSvgElement(id, text, text.bounds, paint);
                if (text != null) {
                    if (text.xCoords != null && text.xCoords.length > 0) {
                        // Draw each glyph separately according to their x coordinates
                        int i = 0;
                        Float thisX = parseFloat(text.xCoords[0], null);
                        Float nextX = 0f;
                        if (thisX != null) {
                            float x = thisX;
                            for (i = 0; i < text.text.length(); i++) {
                                if (i >= text.xCoords.length) {
                                    // Break early so we can draw the rest of the characters in one go
                                    i--;
                                    break;
                                }
                                if (i + 1 < text.xCoords.length) {
                                    nextX = parseFloat(text.xCoords[i + 1], null);
                                    if (nextX == null) {
                                        // Break early so we can draw the rest of the characters in one go
                                        i--;
                                        break;
                                    }
                                }
                                // Draw the glyph
                                String s = new String(new char[]{text.text.charAt(i)});
                                canvas.drawText(s, x + text.xOffset, text.y + text.yOffset, paint);
                                x = nextX;
                            }
                        }
                        if (i < text.text.length()) {
                            canvas.drawText(text.text.substring(i), x + text.xOffset, text.y + text.yOffset, paint);
                        }
                    } else {
                        // Draw the entire string
                        canvas.drawText(text.text, text.x + text.xOffset, text.y + text.yOffset, paint);
                    }
                    onSvgElementDrawn(text.id, text, paint);
                }
            }
        }

        private Align getTextAlign(Attributes atts) {
            String align = getStringAttr("text-anchor", atts);
            if (align == null) {
                return null;
            }
            if ("middle".equals(align)) {
                return Align.CENTER;
            } else if ("end".equals(align)) {
                return Align.RIGHT;
            } else {
                return Align.LEFT;
            }
        }

        private Typeface setTypeface(Attributes atts, Properties props, AssetManager assetManager, Typeface defaultTypeface) {
            // Prefer a dedicated attribute
            String family = getStringAttr("font-family", atts);
            if (family == null) {
                // Fall back to reading from "style" attribute
                family = props.getString("font-family");
            }
            // Prefer a dedicated attribute
            String style = getStringAttr("font-style", atts);
            if (style == null) {
                // Fall back to reading from "style" attribute
                style = props.getString("font-style");
            }
            // Prefer a dedicated attribute
            String weight = getStringAttr("font-weight", atts);
            if (weight == null) {
                // Fall back to reading from "style" attribute
                weight = props.getString("font-weight");
            }

            // Set the style parameters
            int styleParam = Typeface.NORMAL;
            if ("italic".equals(style)) {
                styleParam |= Typeface.ITALIC;
            }
            if ("bold".equals(weight)) {
                styleParam |= Typeface.BOLD;
            }

            Typeface plain;
            if (family != null) {
                // Attempt to load the typeface
                if (assetManager != null) {
                    Pattern pattern = Pattern.compile("'(.+?)'(?:,'(.+?)')*");
                    Matcher matcher = pattern.matcher(family);
                    if (matcher.matches()) {
                        for (int i = 1; i < matcher.groupCount() + 1; i++) {
                            if (matcher.group(i) != null) {
                                family = matcher.group(i);
                            }
                        }
                    }
                    // Compose a filename
                    String typefaceFile = "fonts/" + family + ".ttf";
                    try {
                        plain = Typeface.createFromAsset(assetManager, typefaceFile);
                        if (LOG_LEVEL >= LOG_LEVEL_INFO) {
                            Log.d(TAG, "Loaded typeface from assets: " + typefaceFile);
                        }
                    } catch (RuntimeException e) {
                        boolean found = true;
                        try {
                            String[] fonts = assetManager.list("fonts/");
                            found = false;
                            for (String font : fonts) {
                                if (typefaceFile.equals(font)) {
                                    found = true;
                                }
                            }
                        } catch (IOException e1) {
                            if (LOG_LEVEL >= LOG_LEVEL_ERROR) {
                                Log.e(TAG, "Failed listing assets directory for /fonts", e);
                            }
                        }
                        if (!found) {
                            if (LOG_LEVEL >= LOG_LEVEL_ERROR) {
                                Log.e(TAG, "Typeface is missing from assets: " + typefaceFile);
                            }
                        } else {
                            if (LOG_LEVEL >= LOG_LEVEL_ERROR) {
                                Log.e(TAG, "Failed to create typeface from assets: " + typefaceFile, e);
                            }
                        }
                        plain = null;
                    }
                    if (plain != null) {
                        // Adapt the type face with the style
                        return Typeface.create(plain, styleParam);
                    }
                } else {
                    if (LOG_LEVEL >= LOG_LEVEL_ERROR) {
                        Log.e(TAG, "Typefaces can only be loaded if assets are provided; " +
                                "invoke " + SvgCompact.class.getSimpleName() + " with .withAssets()");
                    }
                }
            }
            if (defaultTypeface == null) {
                return Typeface.create(family, styleParam);
            } else {
                return Typeface.create(defaultTypeface, styleParam);
            }
        }
    }

    public static void checkAssumedUnits(String unit) {
        if (sAssumedUnit == null) {
            sAssumedUnit = unit;
        }
        if (!sAssumedUnit.equals(unit)) {
            throw new IllegalStateException("Mixing units; SVG contains both " + sAssumedUnit + " and " + unit);
        }
    }

    public interface DrawableCallback {

        void onDrawableReady(SvgDrawable svgDrawable);

    }

    public interface PictureCallback {

        void onPictureReady(SvgPicture svgPicture);

    }

}
