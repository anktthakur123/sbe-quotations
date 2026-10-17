package com.sidhbaba.quotations;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Fills the supplied quotation layout. The static background is rendered from
 * the user's reference PDF, so its colors, fixed text, signature, table borders,
 * terms and footer remain visually identical to the reference.
 */
final class QuotePdfRenderer {
    private static final int PAGE_W = 612;
    private static final int PAGE_H = 792;
    private static final int BLUE = Color.rgb(61, 82, 139);
    private static final int WHITE = Color.WHITE;
    private static final int GRID_GREY = Color.rgb(242, 242, 242);
    private static final float TABLE_ROW_STEP = 12.6f;
    private static final float FIRST_ROW_BASELINE = 294.5f;
    private static final float[] CELL_LEFT = {79.35f, 109.0f, 350.72f, 420.56f, 468.95f};
    private static final float[] CELL_RIGHT = {108.13f, 349.80f, 419.70f, 468.09f, 527.10f};
    private static final Typeface BODY = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    private static final Typeface BOLD = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    private QuotePdfRenderer() {}

    static void create(Context context, JSONObject quote, File outputFile) throws Exception {
        JSONArray jsonItems = quote.optJSONArray("items");
        if (jsonItems == null || jsonItems.length() == 0) throw new IllegalArgumentException("At least one item is required.");
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < jsonItems.length(); i++) items.add(jsonItems.optJSONObject(i) == null ? new JSONObject() : jsonItems.getJSONObject(i));

        Bitmap background;
        try (InputStream in = context.getAssets().open("quotation_background.png")) {
            background = BitmapFactory.decodeStream(in);
        }
        if (background == null) throw new IllegalStateException("Quotation template background is missing.");

        int pageCount = Math.max(1, (int) Math.ceil(items.size() / 15.0));
        PdfDocument document = new PdfDocument();
        try {
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageIndex + 1).create();
                PdfDocument.Page page = document.startPage(info);
                Canvas canvas = page.getCanvas();
                canvas.drawColor(WHITE);
                Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                canvas.drawBitmap(background, null, new RectF(0, 0, PAGE_W, PAGE_H), bitmapPaint);

                fillHeaderFields(canvas, quote);
                int from = pageIndex * 15;
                int to = Math.min(from + 15, items.size());
                fillTable(canvas, items, from, to);
                if (pageIndex != pageCount - 1) clearTermsAndSignature(canvas);
                document.finishPage(page);
            }
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Could not create output folder.");
            try (FileOutputStream stream = new FileOutputStream(outputFile, false)) {
                document.writeTo(stream);
                stream.flush();
            }
        } finally {
            document.close();
            background.recycle();
        }
    }

    private static void fillHeaderFields(Canvas canvas, JSONObject quote) {
        // Only the variable value areas are masked; the reference PDF's fixed labels remain intact.
        erase(canvas, 419.8f, 111.3f, 526f, 123.5f, WHITE);   // Date value
        erase(canvas, 419.8f, 123.5f, 526f, 136.0f, WHITE);   // Quotation number
        erase(canvas, 419.8f, 136.0f, 570f, 148.7f, WHITE);   // Valid-until value

        Paint topText = paint(INK, 8.4f, BODY, Paint.Align.LEFT);
        drawFitLine(canvas, prettyDate(quote.optString("date", "")), 421.75f, 122.60f, 145f, topText);
        drawFitLine(canvas, quote.optString("quoteNo", ""), 421.75f, 134.72f, 103f, topText);
        drawFitLine(canvas, prettyDate(quote.optString("validUntil", "")), 421.75f, 146.84f, 144f, topText);

        erase(canvas, 79.45f, 196.2f, 350.4f, 244.2f, WHITE); // Customer value block; bar above is untouched
        JSONObject customer = quote.optJSONObject("customer");
        if (customer == null) customer = new JSONObject();
        Paint customerPaint = paint(INK, 8.8f, BODY, Paint.Align.LEFT);
        String customerName = customer.optString("name", "").trim();
        String company = customer.optString("company", "").trim();
        String address = customer.optString("address", "").trim();
        String gstin = customer.optString("gstin", "").trim();
        if (!customerName.isEmpty()) drawFitLine(canvas, customerName, 80.54f, 206.72f, 265f, customerPaint);
        drawFitLine(canvas, company, 80.54f, 218.36f, 265f, customerPaint);
        drawFitLine(canvas, address, 80.54f, 230.00f, 265f, customerPaint);
        drawFitLine(canvas, gstin.isEmpty() ? "GSTIN:" : "GSTIN: " + gstin, 80.54f, 241.16f, 265f, customerPaint);

        // Preserve the reference template's fixed "Subjet:" label and replace only the subject value.
        erase(canvas, 110.2f, 249.8f, 530f, 264.3f, WHITE);
        String subject = quote.optString("subject", "").trim();
        if (subject.isEmpty()) subject = "QUOTATION FOR SPL GAUGE";
        Paint subjectPaint = paint(INK, 9.3f, BOLD, Paint.Align.LEFT);
        drawFitLine(canvas, subject, 111.72f, 261.83f, 415f, subjectPaint);
    }

    private static void fillTable(Canvas canvas, List<JSONObject> items, int from, int to) {
        Paint white = fill(WHITE);
        Paint shade = fill(GRID_GREY);
        Paint srPaint = paint(INK, 8.6f, BODY, Paint.Align.LEFT);
        Paint bodyPaint = paint(INK, 8.65f, BODY, Paint.Align.LEFT);
        Paint qtyPaint = paint(INK, 8.6f, BODY, Paint.Align.LEFT);
        Paint amountPaint = paint(INK, 8.5f, BODY, Paint.Align.RIGHT);

        for (int slot = 0; slot < 15; slot++) {
            float top = 283.02f + slot * TABLE_ROW_STEP;
            float bottom = 294.72f + slot * TABLE_ROW_STEP;
            // White-out variable cell contents without covering the reference grid lines.
            for (int col = 0; col < CELL_LEFT.length; col++) {
                Paint cellFill = (col == 3 && (slot + 1) % 2 == 0) ? shade : white;
                canvas.drawRect(CELL_LEFT[col], top, CELL_RIGHT[col], bottom, cellFill);
            }
            int index = from + slot;
            if (index >= to) continue;
            JSONObject item = items.get(index);
            float baseline = FIRST_ROW_BASELINE + slot * TABLE_ROW_STEP;
            canvas.drawText(String.valueOf(index + 1), 80.54f, baseline - 0.35f, srPaint);
            drawFitLine(canvas, item.optString("description", "").trim(), 110.30f, baseline, 236.5f, bodyPaint);
            drawFitLine(canvas, item.optString("itemCode", "").trim(), 352.03f, baseline, 64f, bodyPaint);
            canvas.drawText("01nos", 421.87f, baseline, qtyPaint);
            double amount = item.optDouble("price", 0d);
            drawRightFitLine(canvas, formatCurrency(amount), 525.80f, baseline - 0.25f, 57f, amountPaint);
        }
    }

    private static void clearTermsAndSignature(Canvas canvas) {
        // A continuation page keeps the same quote header and table but reserves legal/signature block for the last page.
        erase(canvas, 78.5f, 483.15f, 528.1f, 716.0f, WHITE);
        erase(canvas, 420.05f, 472.03f, 527.7f, 483.55f, WHITE); // small shaded strip below final table row
    }

    private static void erase(Canvas canvas, float left, float top, float right, float bottom, int color) {
        canvas.drawRect(left, top, right, bottom, fill(color));
    }

    private static Paint paint(int color, float size, Typeface face, Paint.Align align) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.DITHER_FLAG);
        p.setColor(color); p.setTextSize(size); p.setTypeface(face); p.setTextAlign(align);
        return p;
    }

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color); p.setStyle(Paint.Style.FILL);
        return p;
    }

    /** Draw the complete value; scale horizontally only when the source field is longer than the template column. */
    private static void drawFitLine(Canvas canvas, String text, float x, float baseline, float maxWidth, Paint sourcePaint) {
        if (text == null || text.trim().isEmpty()) return;
        Paint p = new Paint(sourcePaint);
        float measured = p.measureText(text);
        if (measured > maxWidth && measured > 0f) p.setTextScaleX(maxWidth / measured);
        canvas.drawText(text, x, baseline, p);
    }

    private static void drawRightFitLine(Canvas canvas, String text, float right, float baseline, float maxWidth, Paint sourcePaint) {
        Paint p = new Paint(sourcePaint);
        float measured = p.measureText(text);
        if (measured > maxWidth && measured > 0f) p.setTextScaleX(maxWidth / measured);
        canvas.drawText(text, right, baseline, p);
    }

    private static String formatCurrency(double value) {
        NumberFormat nf = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        nf.setMinimumFractionDigits(2); nf.setMaximumFractionDigits(2);
        return "₹ " + nf.format(value);
    }

    private static String prettyDate(String iso) {
        if (iso == null || iso.trim().isEmpty()) return "";
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso);
            return new SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH).format(d);
        } catch (ParseException e) { return iso; }
    }

    private static final int INK = Color.rgb(30, 30, 30);
}
