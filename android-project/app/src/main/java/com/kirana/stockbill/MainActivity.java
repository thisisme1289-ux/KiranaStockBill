package com.kirana.stockbill;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_DOC_SCAN = 12;
    private static final int REQ_PICK_FILE = 13;
    private static final String PREFS = "kirana_stock_bill";
    private static final String KEY_ITEMS = "items_v1";
    private static final String KEY_PURCHASES = "purchases_v1";
    private static final String KEY_SALES = "sales_v1";

    private SharedPreferences prefs;
    private LinearLayout root;
    private Uri pendingPhotoUri;
    private File pendingPhotoFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        seedIfEmpty();
        handleSharedIntent(getIntent());
        showHome();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleSharedIntent(intent);
    }

    private void handleSharedIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) runOcrFromUri(uri);
    }

    private void setPage(String title) {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.setBackgroundColor(Color.rgb(247, 248, 244));
        scroll.addView(root);
        setContentView(scroll);

        TextView heading = text(title, 24, true);
        heading.setTextColor(Color.rgb(13, 68, 59));
        root.addView(heading);
    }

    private void showHome() {
        setPage("Kirana Stock Bill");
        addSpace(8);
        root.addView(text("Purchase receipts, stock, sales billing and GST totals in one place.", 15, false));
        addSpace(12);

        LinearLayout stats = card();
        int itemCount = items().length();
        double stockValue = 0;
        int lowStock = 0;
        JSONArray list = items();
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            stockValue += item.optDouble("stock") * item.optDouble("salePrice");
            if (item.optDouble("stock") <= item.optDouble("lowStock", 0)) lowStock++;
        }
        stats.addView(text("Items: " + itemCount, 18, true));
        stats.addView(text("Stock sale value: Rs " + money(stockValue), 16, false));
        stats.addView(text("Low stock: " + lowStock + "    Purchases: " + purchases().length() + "    Sales: " + sales().length(), 16, false));
        root.addView(stats);

        addButton("Scan / Import Purchase Bill", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showPurchaseHub(); }
        });
        addButton("Record Sale & Create Bill", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showSaleScreen(); }
        });
        addButton("Inventory", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showInventory(); }
        });
        addButton("Reports", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showReports(); }
        });
        addButton("Bills & Parties", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showBillsAndParties(); }
        });
        addButton("Backup / Export", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showBackupExport(); }
        });
    }

    private void showPurchaseHub() {
        setPage("Purchase Stock");
        addBack();
        root.addView(text("Scan bills on-device with auto crop, cleanup, rotation and page review. OCR results are always reviewed before stock is saved.", 15, false));
        addButton("Scan Receipt", true, new View.OnClickListener() {
            @Override public void onClick(View v) { takePhoto(); }
        });
        addButton("Import Image / PDF", false, new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        });
        addButton("Manual Purchase Entry", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showPurchaseReview(new ArrayList<PurchaseRow>(), ""); }
        });
    }

    private void takePhoto() {
        setPage("Opening Scanner");
        root.addView(text("Starting on-device document scanner. It will crop, clean, rotate, and let you review pages before OCR.", 15, false));
        GmsDocumentScannerOptions options = new GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG, GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build();
        GmsDocumentScanner scanner = GmsDocumentScanning.getClient(options);
        scanner.getStartScanIntent(this)
            .addOnSuccessListener(new OnSuccessListener<IntentSender>() {
                @Override public void onSuccess(IntentSender intentSender) {
                    try {
                        startIntentSenderForResult(intentSender, REQ_DOC_SCAN, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        toast("Scanner could not start: " + e.getMessage());
                        showPurchaseHub();
                    }
                }
            })
            .addOnFailureListener(new OnFailureListener() {
                @Override public void onFailure(Exception e) {
                    toast("Document scanner unavailable on this device. Use Import Image / PDF.");
                    showPurchaseHub();
                }
            });
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
        startActivityForResult(intent, REQ_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        if (requestCode == REQ_DOC_SCAN) {
            handleDocumentScannerResult(data);
        } else if (requestCode == REQ_PICK_FILE && data != null && data.getData() != null) {
            runOcrFromUri(data.getData());
        }
    }

    private void handleDocumentScannerResult(Intent data) {
        GmsDocumentScanningResult result = GmsDocumentScanningResult.fromActivityResultIntent(data);
        if (result == null) {
            toast("No scan returned.");
            showPurchaseHub();
            return;
        }
        try {
            ArrayList<InputImage> images = new ArrayList<>();
            if (result.getPages() != null) {
                for (GmsDocumentScanningResult.Page page : result.getPages()) {
                    if (page.getImageUri() != null) {
                        images.add(InputImage.fromFilePath(this, page.getImageUri()));
                    }
                }
            }
            if (!images.isEmpty()) {
                processOcrImages(images);
            } else if (result.getPdf() != null && result.getPdf().getUri() != null) {
                runOcrFromUri(result.getPdf().getUri());
            } else {
                toast("Scan did not return readable pages.");
                showPurchaseHub();
            }
        } catch (Exception ex) {
            toast("Could not read scanned pages: " + ex.getMessage());
            showPurchaseHub();
        }
    }

    private void runOcrFromUri(final Uri uri) {
        setPage("Reading Bill");
        root.addView(text("Extracting printed text from the bill. Multi-page PDFs are scanned page by page.", 15, false));
        try {
            String type = getContentResolver().getType(uri);
            if (isPdfUri(uri, type)) {
                processOcrImages(renderPdfPages(uri));
            } else {
                ArrayList<InputImage> images = new ArrayList<>();
                images.add(InputImage.fromFilePath(this, uri));
                processOcrImages(images);
            }
        } catch (Exception ex) {
            toast("Could not read file: " + ex.getMessage());
            showPurchaseReview(new ArrayList<PurchaseRow>(), "");
        }
    }

    private boolean isPdfUri(Uri uri, String type) {
        if (type != null && type.toLowerCase(Locale.US).contains("pdf")) return true;
        String value = uri == null ? "" : uri.toString().toLowerCase(Locale.US);
        return value.endsWith(".pdf") || value.contains(".pdf?");
    }

    private void processOcrImages(final ArrayList<InputImage> images) {
        if (images.isEmpty()) {
            showPurchaseReview(new ArrayList<PurchaseRow>(), "");
            return;
        }
        final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        final ArrayList<RecognizedLine> allLines = new ArrayList<>();
        final StringBuilder raw = new StringBuilder();
        processOcrPage(recognizer, images, 0, allLines, raw);
    }

    private void processOcrPage(final TextRecognizer recognizer, final ArrayList<InputImage> images, final int index,
                                final ArrayList<RecognizedLine> allLines, final StringBuilder raw) {
        if (index >= images.size()) {
            showPurchaseReview(parseReceipt(allLines, raw.toString()), raw.toString());
            return;
        }
        recognizer.process(images.get(index))
            .addOnSuccessListener(new OnSuccessListener<Text>() {
                @Override public void onSuccess(Text text) {
                    raw.append("\n--- PAGE ").append(index + 1).append(" ---\n").append(text.getText()).append("\n");
                    for (Text.TextBlock block : text.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            Rect box = line.getBoundingBox();
                            allLines.add(new RecognizedLine(line.getText(), box, index));
                        }
                    }
                    processOcrPage(recognizer, images, index + 1, allLines, raw);
                }
            })
            .addOnFailureListener(new OnFailureListener() {
                @Override public void onFailure(Exception e) {
                    if (index == 0) {
                        toast("OCR failed. You can enter bill manually.");
                        showPurchaseReview(new ArrayList<PurchaseRow>(), raw.toString());
                    } else {
                        processOcrPage(recognizer, images, index + 1, allLines, raw);
                    }
                }
            });
    }

    private ArrayList<InputImage> renderPdfPages(Uri uri) throws Exception {
        ArrayList<InputImage> images = new ArrayList<>();
        ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
        android.graphics.pdf.PdfRenderer renderer = new android.graphics.pdf.PdfRenderer(fd);
        for (int i = 0; i < renderer.getPageCount(); i++) {
            android.graphics.pdf.PdfRenderer.Page page = renderer.openPage(i);
            int scale = 3;
            Bitmap bitmap = Bitmap.createBitmap(page.getWidth() * scale, page.getHeight() * scale, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            images.add(InputImage.fromBitmap(bitmap, 0));
        }
        renderer.close();
        fd.close();
        return images;
    }

    private Bitmap renderFirstPdfPage(Uri uri) throws Exception {
        ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r");
        android.graphics.pdf.PdfRenderer renderer = new android.graphics.pdf.PdfRenderer(fd);
        android.graphics.pdf.PdfRenderer.Page page = renderer.openPage(0);
        Bitmap bitmap = Bitmap.createBitmap(page.getWidth() * 2, page.getHeight() * 2, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        page.close();
        renderer.close();
        fd.close();
        return bitmap;
    }

    private void showPurchaseReview(List<PurchaseRow> parsedRows, String rawText) {
        setPage("Review Purchase");
        addBackTo(new View.OnClickListener() { @Override public void onClick(View v) { showPurchaseHub(); }});
        final EditText supplier = input("Supplier name", "");
        final EditText billNo = input("Bill number", "");
        final EditText date = input("Date", today());
        final EditText billDiscount = input("Bill discount Rs", "0");
        root.addView(label("Supplier")); root.addView(supplier);
        root.addView(label("Bill No")); root.addView(billNo);
        root.addView(label("Date")); root.addView(date);
        root.addView(label("Whole bill discount")); root.addView(billDiscount);

        final LinearLayout rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        final ArrayList<RowEditor> editors = new ArrayList<>();
        root.addView(text("Items detected: " + parsedRows.size(), 18, true));
        if (parsedRows.isEmpty()) {
            root.addView(text("No item rows were detected automatically. Use Raw OCR Text to check whether the scan was readable, or add rows manually.", 14, false));
        }
        for (PurchaseRow row : parsedRows) addPurchaseRowEditor(rowsBox, editors, row);
        if (parsedRows.isEmpty()) addPurchaseRowEditor(rowsBox, editors, new PurchaseRow());
        root.addView(rowsBox);

        addButton("Add Item Row", false, new View.OnClickListener() {
            @Override public void onClick(View v) { addPurchaseRowEditor(rowsBox, editors, new PurchaseRow()); }
        });
        addButton("Confirm Stock In", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmPurchase(supplier.getText().toString(), billNo.getText().toString(), date.getText().toString(),
                    num(billDiscount.getText().toString()), editors);
            }
        });
        if (!TextUtils.isEmpty(rawText)) {
            addButton("View Raw OCR Text", false, new View.OnClickListener() {
                @Override public void onClick(View v) { showTextDialog("Raw OCR Text", rawText); }
            });
        }
    }

    private void addPurchaseRowEditor(LinearLayout rowsBox, ArrayList<RowEditor> editors, PurchaseRow row) {
        RowEditor editor = new RowEditor();
        LinearLayout card = card();
        editor.name = input("Item name", row.name);
        editor.qty = input("Qty", row.qty > 0 ? money(row.qty) : "");
        editor.unit = input("Unit", row.unit);
        editor.purchaseRate = input("Purchase rate", row.purchaseRate > 0 ? money(row.purchaseRate) : "");
        editor.salePrice = input("Selling price", row.salePrice > 0 ? money(row.salePrice) : "");
        editor.gst = input("GST %", row.gst > 0 ? money(row.gst) : "5");
        card.addView(label("Item")); card.addView(editor.name);
        card.addView(label("Qty / Unit")); card.addView(horizontal(editor.qty, editor.unit));
        card.addView(label("Purchase rate / Selling price")); card.addView(horizontal(editor.purchaseRate, editor.salePrice));
        card.addView(label("GST %")); card.addView(editor.gst);
        Button remove = button("Remove", false);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                rowsBox.removeView(card);
                editors.remove(editor);
            }
        });
        card.addView(remove);
        editors.add(editor);
        rowsBox.addView(card);
    }

    private void confirmPurchase(String supplier, String billNo, String date, double billDiscount, ArrayList<RowEditor> editors) {
        JSONArray current = items();
        JSONArray purchaseRows = new JSONArray();
        int count = 0;
        for (RowEditor editor : editors) {
            String name = cleanName(editor.name.getText().toString());
            double qty = num(editor.qty.getText().toString());
            double purchaseRate = num(editor.purchaseRate.getText().toString());
            double salePrice = num(editor.salePrice.getText().toString());
            double gst = num(editor.gst.getText().toString());
            String unit = editor.unit.getText().toString().trim();
            if (TextUtils.isEmpty(name) && qty == 0) continue;
            if (TextUtils.isEmpty(name) || qty <= 0) {
                toast("Each purchase row needs item and quantity.");
                return;
            }
            JSONObject item = findItem(current, name);
            if (item == null) {
                item = new JSONObject();
                put(item, "id", "item-" + System.currentTimeMillis() + "-" + count);
                put(item, "name", name);
                put(item, "category", "General");
                put(item, "unit", TextUtils.isEmpty(unit) ? "pcs" : unit);
                put(item, "stock", 0);
                put(item, "lowStock", 0);
                put(item, "aliases", new JSONArray().put(name));
                current.put(item);
            } else {
                addAlias(item, name);
            }
            put(item, "stock", item.optDouble("stock") + qty);
            if (purchaseRate > 0) put(item, "purchaseRate", purchaseRate);
            if (salePrice > 0) put(item, "salePrice", salePrice); else if (item.optDouble("salePrice") == 0 && purchaseRate > 0) put(item, "salePrice", Math.round(purchaseRate * 1.12 * 100.0) / 100.0);
            if (gst >= 0) put(item, "gst", gst);
            if (!TextUtils.isEmpty(unit)) put(item, "unit", unit);
            JSONObject pr = new JSONObject();
            put(pr, "name", name); put(pr, "qty", qty); put(pr, "unit", item.optString("unit"));
            put(pr, "purchaseRate", purchaseRate); put(pr, "salePrice", item.optDouble("salePrice")); put(pr, "gst", gst);
            purchaseRows.put(pr);
            count++;
        }
        if (count == 0) { toast("Add at least one item."); return; }
        save(KEY_ITEMS, current);
        JSONArray purchases = purchases();
        JSONObject purchase = new JSONObject();
        put(purchase, "id", "purchase-" + System.currentTimeMillis());
        put(purchase, "supplier", supplier);
        put(purchase, "billNo", billNo);
        put(purchase, "date", TextUtils.isEmpty(date) ? today() : date);
        put(purchase, "discount", billDiscount);
        put(purchase, "rows", purchaseRows);
        purchases.put(purchase);
        save(KEY_PURCHASES, purchases);
        toast("Stock updated: " + count + " item rows.");
        showInventory();
    }

    private void showInventory() {
        showInventory("");
    }

    private void showInventory(final String filter) {
        setPage("Inventory");
        addBack();
        final EditText search = input("Search item", filter);
        root.addView(label("Search"));
        root.addView(horizontal(search, button("Go", false)));
        Button go = (Button) ((ViewGroup) search.getParent()).getChildAt(1);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showInventory(search.getText().toString()); }
        });
        addButton("Add Item Manually", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showItemDialog(null); }
        });
        JSONArray list = items();
        String needle = normalize(filter);
        int shown = 0;
        if (list.length() == 0) root.addView(text("No items yet.", 15, false));
        for (int i = 0; i < list.length(); i++) {
            final JSONObject item = list.optJSONObject(i);
            if (!TextUtils.isEmpty(needle) && !itemMatches(item, needle)) continue;
            shown++;
            LinearLayout c = card();
            c.addView(text(item.optString("name"), 17, true));
            c.addView(text("Stock: " + money(item.optDouble("stock")) + " " + item.optString("unit", "pcs"), 15, false));
            c.addView(text("Purchase Rs " + money(item.optDouble("purchaseRate")) + "  |  Sale Rs " + money(item.optDouble("salePrice")) + "  |  GST " + money(item.optDouble("gst")) + "%", 14, false));
            c.addView(text("Low stock alert: " + money(item.optDouble("lowStock", 0)) + " " + item.optString("unit", "pcs"), 14, false));
            Button edit = button("Edit Item", false);
            edit.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showItemDialog(item); }});
            c.addView(edit);
            root.addView(c);
        }
        if (shown == 0 && list.length() > 0) root.addView(text("No matching items.", 15, false));
    }

    private void showItemDialog(final JSONObject existing) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));
        final EditText name = input("Name", existing == null ? "" : existing.optString("name"));
        final EditText unit = input("Unit", existing == null ? "pcs" : existing.optString("unit", "pcs"));
        final EditText stock = input("Stock", existing == null ? "0" : money(existing.optDouble("stock")));
        final EditText purchase = input("Purchase rate", existing == null ? "0" : money(existing.optDouble("purchaseRate")));
        final EditText sale = input("Sale price", existing == null ? "0" : money(existing.optDouble("salePrice")));
        final EditText gst = input("GST %", existing == null ? "5" : money(existing.optDouble("gst")));
        final EditText lowStock = input("Low stock alert", existing == null ? "0" : money(existing.optDouble("lowStock", 0)));
        final EditText aliases = input("Aliases comma separated", existing == null ? "" : aliasesToText(existing.optJSONArray("aliases")));
        box.addView(label("Item")); box.addView(name);
        box.addView(label("Unit")); box.addView(unit);
        box.addView(label("Stock")); box.addView(stock);
        box.addView(label("Purchase rate")); box.addView(purchase);
        box.addView(label("Sale price")); box.addView(sale);
        box.addView(label("GST %")); box.addView(gst);
        box.addView(label("Low stock alert")); box.addView(lowStock);
        box.addView(label("Supplier aliases")); box.addView(aliases);
        new AlertDialog.Builder(this).setTitle(existing == null ? "Add Item" : "Edit Item").setView(box)
            .setPositiveButton("Save", (dialog, which) -> {
                JSONArray arr = items();
                JSONObject item = existing;
                if (item == null) {
                    item = new JSONObject();
                    put(item, "id", "item-" + System.currentTimeMillis());
                    arr.put(item);
                }
                put(item, "name", cleanName(name.getText().toString()));
                put(item, "unit", unit.getText().toString().trim());
                put(item, "stock", num(stock.getText().toString()));
                put(item, "purchaseRate", num(purchase.getText().toString()));
                put(item, "salePrice", num(sale.getText().toString()));
                put(item, "gst", num(gst.getText().toString()));
                put(item, "lowStock", num(lowStock.getText().toString()));
                put(item, "aliases", parseAliases(item.optString("name"), aliases.getText().toString()));
                save(KEY_ITEMS, arr);
                showInventory();
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void showSaleScreen() {
        setPage("Record Sale");
        addBack();
        final EditText customer = input("Walk-in customer optional", "");
        final EditText mobile = input("Mobile optional", "");
        final EditText discount = input("Bill discount Rs", "0");
        root.addView(label("Customer")); root.addView(customer);
        root.addView(label("Mobile")); root.addView(mobile);
        root.addView(label("Whole bill discount")); root.addView(discount);
        final LinearLayout saleBox = new LinearLayout(this);
        saleBox.setOrientation(LinearLayout.VERTICAL);
        final ArrayList<SaleEditor> editors = new ArrayList<>();
        root.addView(text("Sale Items", 18, true));
        root.addView(saleBox);
        addSaleEditor(saleBox, editors);
        addButton("Add Sale Item", false, new View.OnClickListener() {
            @Override public void onClick(View v) { addSaleEditor(saleBox, editors); }
        });
        addButton("Create Bill", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                createSale(customer.getText().toString(), mobile.getText().toString(), num(discount.getText().toString()), editors);
            }
        });
    }

    private void addSaleEditor(LinearLayout saleBox, ArrayList<SaleEditor> editors) {
        SaleEditor editor = new SaleEditor();
        LinearLayout c = card();
        JSONArray itemArr = items();
        List<String> names = new ArrayList<>();
        names.add("Select item");
        for (int i = 0; i < itemArr.length(); i++) names.add(itemArr.optJSONObject(i).optString("name"));
        editor.spinner = new Spinner(this);
        editor.spinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
        editor.qty = input("Qty", "1");
        editor.rate = input("Rate", "");
        editor.gst = input("GST %", "");
        editor.spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position <= 0) return;
                JSONObject item = findItem(items(), names.get(position));
                if (item != null) {
                    editor.rate.setText(money(item.optDouble("salePrice")));
                    editor.gst.setText(money(item.optDouble("gst")));
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        c.addView(label("Item")); c.addView(editor.spinner);
        c.addView(label("Qty / Rate")); c.addView(horizontal(editor.qty, editor.rate));
        c.addView(label("GST %")); c.addView(editor.gst);
        Button remove = button("Remove", false);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                saleBox.removeView(c);
                editors.remove(editor);
            }
        });
        c.addView(remove);
        editors.add(editor);
        saleBox.addView(c);
    }

    private void createSale(String customer, String mobile, double discount, ArrayList<SaleEditor> editors) {
        JSONArray itemArr = items();
        JSONArray rows = new JSONArray();
        double subtotal = 0, tax = 0;
        for (SaleEditor ed : editors) {
            if (ed.spinner.getSelectedItemPosition() <= 0) continue;
            String name = ed.spinner.getSelectedItem().toString();
            JSONObject item = findItem(itemArr, name);
            double qty = num(ed.qty.getText().toString());
            double rate = num(ed.rate.getText().toString());
            double gst = num(ed.gst.getText().toString());
            if (item == null || qty <= 0 || rate <= 0) { toast("Check sale item rows."); return; }
            if (item.optDouble("stock") < qty) { toast("Not enough stock for " + name); return; }
            double line = qty * rate;
            double lineTax = line * gst / 100.0;
            subtotal += line;
            tax += lineTax;
            put(item, "stock", item.optDouble("stock") - qty);
            JSONObject row = new JSONObject();
            put(row, "name", name); put(row, "qty", qty); put(row, "unit", item.optString("unit"));
            put(row, "rate", rate); put(row, "gst", gst); put(row, "amount", line); put(row, "tax", lineTax);
            rows.put(row);
        }
        if (rows.length() == 0) { toast("Add at least one sale item."); return; }
        save(KEY_ITEMS, itemArr);
        double total = Math.max(0, subtotal + tax - discount);
        JSONObject sale = new JSONObject();
        put(sale, "id", "sale-" + System.currentTimeMillis());
        put(sale, "date", today());
        put(sale, "customer", TextUtils.isEmpty(customer) ? "Walk-in customer" : customer);
        put(sale, "mobile", mobile);
        put(sale, "discount", discount);
        put(sale, "subtotal", subtotal);
        put(sale, "tax", tax);
        put(sale, "total", total);
        put(sale, "rows", rows);
        JSONArray saleArr = sales();
        saleArr.put(sale);
        save(KEY_SALES, saleArr);
        showBill(sale);
    }

    private void showBill(final JSONObject sale) {
        setPage("Bill Created");
        addBackTo(new View.OnClickListener() { @Override public void onClick(View v) { showHome(); }});
        final String billText = billText(sale);
        LinearLayout c = card();
        TextView bill = text(billText, 14, false);
        bill.setTypeface(android.graphics.Typeface.MONOSPACE);
        c.addView(bill);
        root.addView(c);
        addButton("Save PDF Bill", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                File pdf = writeBillPdf(sale, billText);
                if (pdf != null) toast("Saved: " + pdf.getAbsolutePath());
            }
        });
        addButton("Share PDF Bill", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                File pdf = writeBillPdf(sale, billText);
                if (pdf == null) return;
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".provider", pdf);
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("application/pdf");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(share, "Share bill"));
            }
        });
    }

    private File writeBillPdf(JSONObject sale, String billText) {
        try {
            File dir = new File(getExternalFilesDir(null), "bills");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, sale.optString("id") + ".pdf");
            PdfDocument doc = new PdfDocument();
            Paint paint = new Paint();
            paint.setColor(Color.BLACK);
            paint.setTextSize(12);
            PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(595, 842, 1).create());
            Canvas canvas = page.getCanvas();
            int y = 36;
            for (String line : billText.split("\\n")) {
                canvas.drawText(line, 32, y, paint);
                y += 18;
                if (y > 800) break;
            }
            doc.finishPage(page);
            FileOutputStream out = new FileOutputStream(file);
            doc.writeTo(out);
            out.close();
            doc.close();
            return file;
        } catch (Exception ex) {
            toast("PDF failed: " + ex.getMessage());
            return null;
        }
    }

    private String billText(JSONObject sale) {
        StringBuilder sb = new StringBuilder();
        sb.append("KIRANA STOCK BILL\n");
        sb.append("Date: ").append(sale.optString("date")).append("\n");
        sb.append("Bill: ").append(sale.optString("id")).append("\n");
        sb.append("Customer: ").append(sale.optString("customer")).append("\n");
        if (!TextUtils.isEmpty(sale.optString("mobile"))) sb.append("Mobile: ").append(sale.optString("mobile")).append("\n");
        sb.append("--------------------------------\n");
        JSONArray rows = sale.optJSONArray("rows");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            sb.append(r.optString("name")).append("\n");
            sb.append(money(r.optDouble("qty"))).append(" ").append(r.optString("unit")).append(" x Rs ")
                .append(money(r.optDouble("rate"))).append("  GST ").append(money(r.optDouble("gst"))).append("% = Rs ")
                .append(money(r.optDouble("amount") + r.optDouble("tax"))).append("\n");
        }
        sb.append("--------------------------------\n");
        sb.append("Subtotal: Rs ").append(money(sale.optDouble("subtotal"))).append("\n");
        sb.append("GST: Rs ").append(money(sale.optDouble("tax"))).append("\n");
        sb.append("Discount: Rs ").append(money(sale.optDouble("discount"))).append("\n");
        sb.append("Total: Rs ").append(money(sale.optDouble("total"))).append("\n");
        return sb.toString();
    }

    private void showReports() {
        setPage("Reports");
        addBack();
        JSONArray saleArr = sales();
        JSONArray purchaseArr = purchases();
        double saleTotal = 0, gstTotal = 0, purchaseTotal = 0;
        for (int i = 0; i < saleArr.length(); i++) {
            saleTotal += saleArr.optJSONObject(i).optDouble("total");
            gstTotal += saleArr.optJSONObject(i).optDouble("tax");
        }
        for (int i = 0; i < purchaseArr.length(); i++) {
            JSONArray rows = purchaseArr.optJSONObject(i).optJSONArray("rows");
            if (rows == null) continue;
            for (int j = 0; j < rows.length(); j++) {
                JSONObject r = rows.optJSONObject(j);
                purchaseTotal += r.optDouble("qty") * r.optDouble("purchaseRate");
            }
        }
        LinearLayout c = card();
        c.addView(text("Sales total: Rs " + money(saleTotal), 17, true));
        c.addView(text("Sales GST: Rs " + money(gstTotal), 15, false));
        c.addView(text("Purchase value: Rs " + money(purchaseTotal), 15, false));
        c.addView(text("Purchase bills: " + purchaseArr.length() + "    Sales bills: " + saleArr.length(), 15, false));
        root.addView(c);
        root.addView(text("Recent Sales", 18, true));
        for (int i = saleArr.length() - 1; i >= 0 && i >= saleArr.length() - 8; i--) {
            JSONObject sale = saleArr.optJSONObject(i);
            LinearLayout row = card();
            row.addView(text(sale.optString("date") + " - " + sale.optString("customer"), 15, true));
            row.addView(text("Total Rs " + money(sale.optDouble("total")), 15, false));
            root.addView(row);
        }
    }

    private void showBillsAndParties() {
        setPage("Bills & Parties");
        addBack();
        addButton("Sales Bill History", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showSalesHistory(); }
        });
        addButton("Purchase Bill History", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showPurchaseHistory(); }
        });
        addButton("Suppliers", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showPartySummary(true); }
        });
        addButton("Customers", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showPartySummary(false); }
        });
    }

    private void showSalesHistory() {
        setPage("Sales Bills");
        addBackTo(new View.OnClickListener() { @Override public void onClick(View v) { showBillsAndParties(); }});
        JSONArray saleArr = sales();
        if (saleArr.length() == 0) root.addView(text("No sales yet.", 15, false));
        for (int i = saleArr.length() - 1; i >= 0; i--) {
            final JSONObject sale = saleArr.optJSONObject(i);
            LinearLayout c = card();
            c.addView(text(sale.optString("date") + " - " + sale.optString("customer"), 16, true));
            c.addView(text("Bill " + sale.optString("id") + "  |  Total Rs " + money(sale.optDouble("total")), 14, false));
            Button view = button("View / Share Bill", false);
            view.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showBill(sale); }});
            c.addView(view);
            root.addView(c);
        }
    }

    private void showPurchaseHistory() {
        setPage("Purchase Bills");
        addBackTo(new View.OnClickListener() { @Override public void onClick(View v) { showBillsAndParties(); }});
        JSONArray arr = purchases();
        if (arr.length() == 0) root.addView(text("No purchases yet.", 15, false));
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject p = arr.optJSONObject(i);
            JSONArray rows = p.optJSONArray("rows");
            double total = 0;
            if (rows != null) {
                for (int j = 0; j < rows.length(); j++) {
                    JSONObject r = rows.optJSONObject(j);
                    total += r.optDouble("qty") * r.optDouble("purchaseRate");
                }
            }
            LinearLayout c = card();
            c.addView(text(p.optString("date") + " - " + blankAs(p.optString("supplier"), "Supplier not set"), 16, true));
            c.addView(text("Bill " + blankAs(p.optString("billNo"), "-") + "  |  Rows " + (rows == null ? 0 : rows.length()) + "  |  Rs " + money(total), 14, false));
            root.addView(c);
        }
    }

    private void showPartySummary(boolean suppliers) {
        setPage(suppliers ? "Suppliers" : "Customers");
        addBackTo(new View.OnClickListener() { @Override public void onClick(View v) { showBillsAndParties(); }});
        Map<String, Double> totals = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        JSONArray source = suppliers ? purchases() : sales();
        for (int i = 0; i < source.length(); i++) {
            JSONObject obj = source.optJSONObject(i);
            String name = suppliers ? blankAs(obj.optString("supplier"), "Supplier not set") : blankAs(obj.optString("customer"), "Walk-in customer");
            double total = suppliers ? purchaseValue(obj) : obj.optDouble("total");
            totals.put(name, (totals.containsKey(name) ? totals.get(name) : 0) + total);
            counts.put(name, (counts.containsKey(name) ? counts.get(name) : 0) + 1);
        }
        if (totals.isEmpty()) root.addView(text("No records yet.", 15, false));
        for (String name : totals.keySet()) {
            LinearLayout c = card();
            c.addView(text(name, 16, true));
            c.addView(text("Bills: " + counts.get(name) + "  |  Total Rs " + money(totals.get(name)), 14, false));
            root.addView(c);
        }
    }

    private void showBackupExport() {
        setPage("Backup / Export");
        addBack();
        root.addView(text("Exports are saved locally and can be shared. They include item master, aliases, stock, purchases and sales.", 15, false));
        addButton("Share Full JSON Backup", true, new View.OnClickListener() {
            @Override public void onClick(View v) { shareBackupJson(); }
        });
        addButton("Share Inventory CSV", false, new View.OnClickListener() {
            @Override public void onClick(View v) { shareInventoryCsv(); }
        });
        addButton("Share Sales CSV", false, new View.OnClickListener() {
            @Override public void onClick(View v) { shareSalesCsv(); }
        });
    }

    private double purchaseValue(JSONObject purchase) {
        double total = 0;
        JSONArray rows = purchase.optJSONArray("rows");
        if (rows == null) return 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            total += r.optDouble("qty") * r.optDouble("purchaseRate");
        }
        return total;
    }

    private String blankAs(String value, String fallback) {
        return TextUtils.isEmpty(cleanName(value)) ? fallback : cleanName(value);
    }

    private void shareBackupJson() {
        JSONObject backup = new JSONObject();
        put(backup, "createdAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        put(backup, "items", items());
        put(backup, "purchases", purchases());
        put(backup, "sales", sales());
        File file = writeTextFile("kirana-backup-" + System.currentTimeMillis() + ".json", backup.toString());
        if (file != null) shareFile(file, "application/json", "Share backup");
    }

    private void shareInventoryCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("Name,Unit,Stock,Low Stock,Purchase Rate,Sale Price,GST,Aliases\n");
        JSONArray arr = items();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            sb.append(csv(item.optString("name"))).append(",");
            sb.append(csv(item.optString("unit"))).append(",");
            sb.append(money(item.optDouble("stock"))).append(",");
            sb.append(money(item.optDouble("lowStock", 0))).append(",");
            sb.append(money(item.optDouble("purchaseRate"))).append(",");
            sb.append(money(item.optDouble("salePrice"))).append(",");
            sb.append(money(item.optDouble("gst"))).append(",");
            sb.append(csv(aliasesToText(item.optJSONArray("aliases")))).append("\n");
        }
        File file = writeTextFile("kirana-inventory-" + System.currentTimeMillis() + ".csv", sb.toString());
        if (file != null) shareFile(file, "text/csv", "Share inventory CSV");
    }

    private void shareSalesCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("Date,Bill,Customer,Mobile,Subtotal,GST,Discount,Total\n");
        JSONArray arr = sales();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject sale = arr.optJSONObject(i);
            sb.append(csv(sale.optString("date"))).append(",");
            sb.append(csv(sale.optString("id"))).append(",");
            sb.append(csv(sale.optString("customer"))).append(",");
            sb.append(csv(sale.optString("mobile"))).append(",");
            sb.append(money(sale.optDouble("subtotal"))).append(",");
            sb.append(money(sale.optDouble("tax"))).append(",");
            sb.append(money(sale.optDouble("discount"))).append(",");
            sb.append(money(sale.optDouble("total"))).append("\n");
        }
        File file = writeTextFile("kirana-sales-" + System.currentTimeMillis() + ".csv", sb.toString());
        if (file != null) shareFile(file, "text/csv", "Share sales CSV");
    }

    private File writeTextFile(String name, String body) {
        try {
            File dir = new File(getExternalFilesDir(null), "exports");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, name);
            FileOutputStream out = new FileOutputStream(file);
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
            return file;
        } catch (Exception ex) {
            toast("Export failed: " + ex.getMessage());
            return null;
        }
    }

    private void shareFile(File file, String type, String title) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(type);
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, title));
    }

    private String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private List<PurchaseRow> parseReceipt(ArrayList<RecognizedLine> lines, String raw) {
        List<PurchaseRow> rows = parsePipeInvoice(raw);
        rows.addAll(parseNumberedInvoice(raw));
        rows.addAll(parseHsnSplitInvoice(raw));
        if (!rows.isEmpty()) return mergeDuplicateRows(rows);
        return parseGenericReceipt(raw);
    }

    private List<PurchaseRow> parsePipeInvoice(String raw) {
        List<PurchaseRow> rows = new ArrayList<>();
        if (raw == null) return rows;
        String[] lines = raw.split("\\r?\\n");
        for (String original : lines) {
            String line = original.trim();
            if (!line.contains("|")) continue;
            String lower = line.toLowerCase(Locale.US);
            if (lower.contains("particulars") || lower.contains("net amount") || lower.contains("subtotal") || lower.contains("total amount")) continue;
            String normalized = line.replace('¦', '|').replace('!', '|');
            String[] cells = normalized.split("\\|");
            ArrayList<String> clean = new ArrayList<>();
            for (String cell : cells) {
                String value = cell.trim();
                if (!TextUtils.isEmpty(value)) clean.add(value);
            }
            if (clean.size() < 8) continue;
            int itemIndex = findPipeItemIndex(clean);
            if (itemIndex < 0 || itemIndex + 6 >= clean.size()) continue;
            String name = cleanName(clean.get(itemIndex));
            if (TextUtils.isEmpty(name) || looksLikeHeader(name)) continue;
            ArrayList<Double> nums = numbersFromCells(clean, itemIndex + 1);
            if (nums.size() < 5) continue;
            double mrp = findLikelyMrp(nums);
            double rate = findLikelyRate(nums);
            double gst = findLikelyGst(nums);
            double amount = findLikelyAmount(nums);
            if (rate <= 0 || amount <= 0) continue;
            PurchaseRow row = new PurchaseRow();
            row.name = name;
            row.unit = guessUnit(name);
            row.purchaseRate = rate;
            row.salePrice = mrp > 0 ? mrp : Math.round(rate * (1 + gst / 100.0) * 100.0) / 100.0;
            row.gst = gst;
            row.qty = deriveQuantity(amount, rate, gst);
            if (row.qty <= 0) row.qty = 1;
            rows.add(row);
        }
        return rows;
    }

    private int findPipeItemIndex(ArrayList<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            String cell = cells.get(i);
            if (cell.matches(".*[A-Za-z].*") && !looksLikeHeader(cell) && !cell.toLowerCase(Locale.US).contains("gst")) {
                return i;
            }
        }
        return -1;
    }

    private ArrayList<Double> numbersFromCells(ArrayList<String> cells, int start) {
        ArrayList<Double> nums = new ArrayList<>();
        for (int i = start; i < cells.size(); i++) {
            double n = numberToken(cells.get(i));
            if (!Double.isNaN(n)) nums.add(n);
        }
        return nums;
    }

    private double findLikelyMrp(ArrayList<Double> nums) {
        if (nums.size() >= 4) return nums.get(nums.size() - 6 >= 0 ? nums.size() - 6 : Math.max(0, nums.size() - 4));
        return 0;
    }

    private double findLikelyRate(ArrayList<Double> nums) {
        if (nums.size() >= 6) return nums.get(nums.size() - 5);
        if (nums.size() >= 3) return nums.get(nums.size() - 2);
        return 0;
    }

    private double findLikelyGst(ArrayList<Double> nums) {
        for (int i = nums.size() - 2; i >= 0; i--) {
            double n = nums.get(i);
            if (n == 0 || n == 2.5 || n == 5 || n == 6 || n == 9 || n == 12 || n == 14 || n == 18 || n == 28) {
                if (n == 2.5 || n == 6 || n == 9 || n == 14) return n * 2;
                return n;
            }
        }
        return 5;
    }

    private double findLikelyAmount(ArrayList<Double> nums) {
        if (nums.isEmpty()) return 0;
        return nums.get(nums.size() - 1);
    }

    private List<PurchaseRow> parseHsnSplitInvoice(String raw) {
        List<PurchaseRow> rows = new ArrayList<>();
        if (raw == null) return rows;
        String[] lines = raw.split("\\r?\\n");
        for (int i = 0; i < lines.length - 1; i++) {
            String first = lines[i].trim();
            String second = lines[i + 1].trim();
            if (!first.matches("^\\d{6,8}\\b.*")) continue;
            if (!second.matches(".*[A-Za-z].*") || looksLikeHeader(second)) continue;
            ArrayList<Double> nums = numbersFromLine(first);
            if (nums.size() < 6) continue;
            PurchaseRow row = new PurchaseRow();
            row.name = cleanName(second);
            row.unit = guessUnit(row.name);
            row.purchaseRate = pickBaseRateFromHsn(nums);
            row.salePrice = pickMrpFromHsn(nums);
            row.gst = pickGstFromHsn(nums);
            double grossOrNet = pickGrossFromHsn(nums);
            row.qty = deriveQuantity(grossOrNet, row.purchaseRate, row.gst);
            if (row.qty <= 0) row.qty = pickCaseUnitsFromHsn(nums);
            if (!TextUtils.isEmpty(row.name) && row.purchaseRate > 0) rows.add(row);
        }
        return rows;
    }

    private List<PurchaseRow> parseNumberedInvoice(String raw) {
        List<PurchaseRow> rows = new ArrayList<>();
        if (raw == null) return rows;
        String[] lines = raw.split("\\r?\\n");
        for (String original : lines) {
            String line = original.replace('|', ' ').trim();
            if (!line.matches("^\\d{1,3}\\s+.*")) continue;
            if (looksLikeHeader(line)) continue;
            PurchaseRow row = parseNumberedInvoiceLine(line);
            if (row != null) rows.add(row);
        }
        return rows;
    }

    private PurchaseRow parseNumberedInvoiceLine(String line) {
        String[] tokens = line.split("\\s+");
        if (tokens.length < 7) return null;
        int serialEnd = 1;
        ArrayList<Double> numericTail = new ArrayList<>();
        int firstTail = tokens.length;
        for (int i = tokens.length - 1; i >= serialEnd; i--) {
            double n = numberToken(tokens[i]);
            if (Double.isNaN(n)) break;
            numericTail.add(0, n);
            firstTail = i;
        }
        if (numericTail.size() < 5 || firstTail <= serialEnd) return null;
        StringBuilder name = new StringBuilder();
        for (int i = serialEnd; i < firstTail; i++) name.append(tokens[i]).append(" ");
        String cleanName = cleanName(name.toString());
        if (TextUtils.isEmpty(cleanName) || looksLikeHeader(cleanName)) return null;
        double amount = numericTail.get(numericTail.size() - 1);
        double gst = findLikelyGst(numericTail);
        double rate = findLikelyRateFromTail(numericTail);
        double mrp = findLikelyMrpFromTail(numericTail, rate);
        if (rate <= 0 || amount <= 0) return null;
        PurchaseRow row = new PurchaseRow();
        row.name = cleanName;
        row.unit = guessUnit(cleanName);
        row.purchaseRate = rate;
        row.salePrice = mrp > 0 ? mrp : Math.round(rate * (1 + gst / 100.0) * 100.0) / 100.0;
        row.gst = gst;
        row.qty = deriveQuantity(amount, rate, gst);
        if (row.qty <= 0) row.qty = 1;
        return row;
    }

    private double findLikelyRateFromTail(ArrayList<Double> tail) {
        if (tail.size() >= 8) return tail.get(5);
        if (tail.size() >= 5) return tail.get(2);
        return 0;
    }

    private double findLikelyMrpFromTail(ArrayList<Double> tail, double rate) {
        if (tail.size() >= 8) return tail.get(4);
        for (Double n : tail) {
            if (n >= rate && n <= rate * 2.5) return n;
        }
        return 0;
    }

    private ArrayList<Double> numbersFromLine(String line) {
        ArrayList<Double> nums = new ArrayList<>();
        String[] parts = line.split("\\s+");
        for (String part : parts) {
            double n = numberToken(part);
            if (!Double.isNaN(n)) nums.add(n);
        }
        return nums;
    }

    private double pickMrpFromHsn(ArrayList<Double> nums) {
        return nums.size() > 1 ? nums.get(1) : 0;
    }

    private double pickBaseRateFromHsn(ArrayList<Double> nums) {
        return nums.size() > 5 ? nums.get(5) : 0;
    }

    private double pickGrossFromHsn(ArrayList<Double> nums) {
        if (nums.size() > 6) return nums.get(6);
        return nums.isEmpty() ? 0 : nums.get(nums.size() - 1);
    }

    private double pickGstFromHsn(ArrayList<Double> nums) {
        for (int i = nums.size() - 1; i >= 0; i--) {
            double n = nums.get(i);
            if (n == 2.5 || n == 6 || n == 9 || n == 14) return n * 2;
            if (n == 5 || n == 12 || n == 18 || n == 28) return n;
        }
        return 5;
    }

    private double pickCaseUnitsFromHsn(ArrayList<Double> nums) {
        if (nums.size() > 3) return Math.max(1, nums.get(2) + nums.get(3));
        return 1;
    }

    private double deriveQuantity(double amount, double rate, double gst) {
        if (amount <= 0 || rate <= 0) return 0;
        double direct = amount / rate;
        double rounded = Math.round(direct);
        if (Math.abs(direct - rounded) < 0.25) return rounded;
        double beforeTax = amount / (rate * (1 + gst / 100.0));
        rounded = Math.round(beforeTax);
        if (Math.abs(beforeTax - rounded) < 0.25) return rounded;
        return Math.round(direct * 100.0) / 100.0;
    }

    private List<PurchaseRow> mergeDuplicateRows(List<PurchaseRow> rows) {
        ArrayList<PurchaseRow> merged = new ArrayList<>();
        for (PurchaseRow row : rows) {
            PurchaseRow existing = null;
            for (PurchaseRow candidate : merged) {
                if (normalize(candidate.name).equals(normalize(row.name))) {
                    existing = candidate;
                    break;
                }
            }
            if (existing == null) {
                merged.add(row);
            } else {
                existing.qty += row.qty;
                if (row.purchaseRate > 0) existing.purchaseRate = row.purchaseRate;
                if (row.salePrice > 0) existing.salePrice = row.salePrice;
                existing.gst = row.gst;
            }
        }
        return merged;
    }

    private boolean looksLikeHeader(String value) {
        String l = value.toLowerCase(Locale.US);
        return l.contains("particular") || l.contains("item name") || l.contains("invoice") || l.contains("bill") ||
            l.contains("total") || l.contains("gst") || l.contains("amount") || l.contains("phone") ||
            l.contains("retailer") || l.contains("address") || l.contains("salesman");
    }

    private List<PurchaseRow> parseGenericReceipt(String raw) {
        List<PurchaseRow> rows = new ArrayList<>();
        if (raw == null) return rows;
        String[] lines = raw.split("\\r?\\n");
        for (String original : lines) {
            String line = original.trim();
            if (line.length() < 6) continue;
            String lower = line.toLowerCase(Locale.US);
            if (lower.contains("total") || lower.contains("subtotal") || lower.contains("invoice") || lower.contains("gstin") || lower.contains("tax invoice") || lower.contains("date")) continue;
            String[] parts = line.split("\\s+");
            List<Double> nums = new ArrayList<>();
            int firstNum = -1;
            for (int i = 0; i < parts.length; i++) {
                double n = numberToken(parts[i]);
                if (!Double.isNaN(n)) {
                    nums.add(n);
                    if (firstNum < 0) firstNum = i;
                }
            }
            if (nums.size() < 2 || firstNum <= 0) continue;
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < firstNum; i++) name.append(parts[i]).append(" ");
            PurchaseRow row = new PurchaseRow();
            row.name = cleanName(name.toString());
            row.unit = guessUnit(row.name);
            if (nums.size() >= 3) {
                row.qty = nums.get(nums.size() - 3);
                row.purchaseRate = nums.get(nums.size() - 2);
                row.salePrice = Math.round(row.purchaseRate * 1.12 * 100.0) / 100.0;
            } else {
                row.qty = 1;
                row.purchaseRate = nums.get(nums.size() - 2);
                row.salePrice = Math.round(row.purchaseRate * 1.12 * 100.0) / 100.0;
            }
            row.gst = guessGst(nums);
            if (!TextUtils.isEmpty(row.name) && row.purchaseRate > 0) rows.add(row);
        }
        return rows;
    }

    private String guessUnit(String name) {
        String l = name.toLowerCase(Locale.US);
        if (l.contains(" kg") || l.endsWith("kg")) return "kg";
        if (l.contains(" gm") || l.contains("gram")) return "g";
        if (l.contains(" ltr") || l.contains(" litre") || l.endsWith("lt")) return "l";
        if (l.contains(" ml")) return "ml";
        if (l.contains("box")) return "box";
        if (l.contains("pkt") || l.contains("pack")) return "pkt";
        return "pcs";
    }

    private double guessGst(List<Double> nums) {
        for (Double n : nums) if (n == 0 || n == 5 || n == 12 || n == 18 || n == 28) return n;
        return 5;
    }

    private double numberToken(String s) {
        String cleaned = s.replaceAll("[^0-9.]", "");
        if (TextUtils.isEmpty(cleaned)) return Double.NaN;
        try { return Double.parseDouble(cleaned); } catch (Exception ex) { return Double.NaN; }
    }

    private JSONArray items() { return load(KEY_ITEMS); }
    private JSONArray purchases() { return load(KEY_PURCHASES); }
    private JSONArray sales() { return load(KEY_SALES); }

    private JSONArray load(String key) {
        try { return new JSONArray(prefs.getString(key, "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    private void save(String key, JSONArray arr) {
        prefs.edit().putString(key, arr.toString()).apply();
    }

    private JSONObject findItem(JSONArray arr, String name) {
        String target = normalize(name);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (normalize(item.optString("name")).equals(target)) return item;
            JSONArray aliases = item.optJSONArray("aliases");
            if (aliases != null) for (int j = 0; j < aliases.length(); j++) if (normalize(aliases.optString(j)).equals(target)) return item;
        }
        return null;
    }

    private boolean itemMatches(JSONObject item, String normalizedNeedle) {
        if (normalize(item.optString("name")).contains(normalizedNeedle)) return true;
        JSONArray aliases = item.optJSONArray("aliases");
        if (aliases != null) {
            for (int i = 0; i < aliases.length(); i++) {
                if (normalize(aliases.optString(i)).contains(normalizedNeedle)) return true;
            }
        }
        return false;
    }

    private void addAlias(JSONObject item, String alias) {
        String clean = cleanName(alias);
        if (TextUtils.isEmpty(clean)) return;
        JSONArray aliases = item.optJSONArray("aliases");
        if (aliases == null) aliases = new JSONArray();
        String target = normalize(clean);
        for (int i = 0; i < aliases.length(); i++) {
            if (normalize(aliases.optString(i)).equals(target)) {
                put(item, "aliases", aliases);
                return;
            }
        }
        aliases.put(clean);
        put(item, "aliases", aliases);
    }

    private String aliasesToText(JSONArray aliases) {
        if (aliases == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < aliases.length(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(aliases.optString(i));
        }
        return sb.toString();
    }

    private JSONArray parseAliases(String itemName, String raw) {
        JSONArray aliases = new JSONArray();
        aliases.put(cleanName(itemName));
        if (raw != null) {
            String[] parts = raw.split(",");
            for (String part : parts) {
                String clean = cleanName(part);
                if (TextUtils.isEmpty(clean)) continue;
                boolean exists = false;
                for (int i = 0; i < aliases.length(); i++) {
                    if (normalize(aliases.optString(i)).equals(normalize(clean))) exists = true;
                }
                if (!exists) aliases.put(clean);
            }
        }
        return aliases;
    }

    private void seedIfEmpty() {
        if (items().length() > 0) return;
        JSONArray arr = new JSONArray();
        addSeed(arr, "Parle-G Biscuit 250g", "pkt", 20, 18, 22, 18);
        addSeed(arr, "Aashirvaad Atta 5kg", "bag", 8, 230, 255, 5);
        addSeed(arr, "Tata Salt 1kg", "pkt", 15, 22, 28, 5);
        addSeed(arr, "Fortune Oil 1L", "bottle", 10, 125, 145, 5);
        addSeed(arr, "Surf Excel 1kg", "pkt", 6, 118, 135, 18);
        save(KEY_ITEMS, arr);
    }

    private void addSeed(JSONArray arr, String name, String unit, double stock, double purchase, double sale, double gst) {
        JSONObject item = new JSONObject();
        put(item, "id", "seed-" + arr.length());
        put(item, "name", name); put(item, "category", "General"); put(item, "unit", unit);
        put(item, "stock", stock); put(item, "purchaseRate", purchase); put(item, "salePrice", sale); put(item, "gst", gst);
        put(item, "lowStock", 2);
        put(item, "aliases", new JSONArray().put(name));
        arr.put(item);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        c.setBackgroundResource(getResources().getIdentifier("card_bg", "drawable", getPackageName()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(8));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(36, 43, 39));
        t.setPadding(0, dp(4), 0, dp(4));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView label(String value) {
        TextView t = text(value, 13, true);
        t.setTextColor(Color.rgb(79, 91, 84));
        return t;
    }

    private EditText input(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setBackgroundResource(getResources().getIdentifier("input_bg", "drawable", getPackageName()));
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        if (hint.toLowerCase(Locale.US).contains("qty") || hint.toLowerCase(Locale.US).contains("rate") || hint.toLowerCase(Locale.US).contains("price") || hint.toLowerCase(Locale.US).contains("gst") || hint.toLowerCase(Locale.US).contains("stock") || hint.toLowerCase(Locale.US).contains("discount")) {
            e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        }
        return e;
    }

    private LinearLayout horizontal(View a, View b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(dp(8), 0, 0, 0);
        row.addView(b, lp);
        return row;
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(primary ? Color.WHITE : Color.rgb(13, 68, 59));
        b.setBackgroundResource(getResources().getIdentifier(primary ? "button_primary" : "button_secondary", "drawable", getPackageName()));
        return b;
    }

    private void addButton(String text, boolean primary, View.OnClickListener listener) {
        Button b = button(text, primary);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        root.addView(b, lp);
    }

    private void addBack() { addBackTo(new View.OnClickListener() { @Override public void onClick(View v) { showHome(); }}); }
    private void addBackTo(View.OnClickListener listener) { addButton("Back", false, listener); }
    private void addSpace(int dp) {
        View v = new View(this);
        root.addView(v, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private void showTextDialog(String title, String body) {
        TextView t = text(body, 13, false);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        new AlertDialog.Builder(this).setTitle(title).setView(t).setPositiveButton("OK", null).show();
    }

    private String today() { return dateFormat.format(new Date()); }
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void put(JSONObject obj, String key, Object value) { try { obj.put(key, value); } catch (JSONException ignored) {} }
    private double num(String s) {
        if (s == null) return 0;
        try { return Double.parseDouble(s.replace(",", "").trim()); } catch (Exception ex) { return 0; }
    }
    private String money(double d) {
        if (Math.abs(d - Math.round(d)) < 0.001) return String.valueOf((long) Math.round(d));
        return String.format(Locale.US, "%.2f", d);
    }
    private String normalize(String s) { return cleanName(s).replaceAll("[^a-z0-9]", "").toLowerCase(Locale.US); }
    private String cleanName(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static class PurchaseRow {
        String name = "";
        String unit = "pcs";
        double qty;
        double purchaseRate;
        double salePrice;
        double gst = 5;
    }

    private static class RowEditor {
        EditText name;
        EditText qty;
        EditText unit;
        EditText purchaseRate;
        EditText salePrice;
        EditText gst;
    }

    private static class SaleEditor {
        Spinner spinner;
        EditText qty;
        EditText rate;
        EditText gst;
    }

    private static class RecognizedLine {
        String text;
        Rect box;
        int page;

        RecognizedLine(String text, Rect box, int page) {
            this.text = text == null ? "" : text;
            this.box = box;
            this.page = page;
        }
    }

    private class ScannerFrameView extends View {
        private final Paint shade = new Paint();
        private final Paint frame = new Paint();
        private final Paint line = new Paint();

        ScannerFrameView(Context context) {
            super(context);
            shade.setColor(Color.argb(120, 0, 0, 0));
            frame.setColor(Color.WHITE);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(dp(3));
            line.setColor(Color.rgb(90, 222, 170));
            line.setStrokeWidth(dp(2));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int padX = dp(22);
            int padY = dp(46);
            Rect receipt = new Rect(padX, padY, getWidth() - padX, getHeight() - padY);
            canvas.drawRect(0, 0, getWidth(), receipt.top, shade);
            canvas.drawRect(0, receipt.bottom, getWidth(), getHeight(), shade);
            canvas.drawRect(0, receipt.top, receipt.left, receipt.bottom, shade);
            canvas.drawRect(receipt.right, receipt.top, getWidth(), receipt.bottom, shade);
            canvas.drawRect(receipt, frame);
            int third = receipt.height() / 3;
            canvas.drawLine(receipt.left, receipt.top + third, receipt.right, receipt.top + third, line);
            canvas.drawLine(receipt.left, receipt.top + third * 2, receipt.right, receipt.top + third * 2, line);
        }
    }
}
