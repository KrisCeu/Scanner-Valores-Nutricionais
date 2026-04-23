package com.my.application;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Size;
import android.graphics.Color;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private TextView txtResult;
    private MaterialButton btnToggleScan, btnCopy, btnShare;
    private View focusAreaView;
    private FloatingActionButton btnFlash;

    private ExecutorService cameraExecutor;
    private boolean isScanning = true;
    private androidx.camera.core.Camera camera;
    private boolean isFlashEnabled = false;
    private ScaleGestureDetector scaleGestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hideSystemUI();

        previewView = findViewById(R.id.previewView);
        txtResult = findViewById(R.id.txtResult);
        btnToggleScan = findViewById(R.id.btnToggleScan);
        btnCopy = findViewById(R.id.btnCopy);
        btnShare = findViewById(R.id.btnShare);
        focusAreaView = findViewById(R.id.focusArea);
        btnFlash = findViewById(R.id.btnFlash);

        setupButtons();
        setupZoom();

        btnFlash.setOnClickListener(v -> toggleFlash());

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 10);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void hideSystemUI() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    private void setupButtons() {
        btnToggleScan.setOnClickListener(v -> {
            isScanning = !isScanning;
            if (isScanning) {
                btnToggleScan.setText("Pausar");
                btnToggleScan.setIconResource(android.R.drawable.ic_media_pause);
                txtResult.setText("Escaneando...");
            } else {
                btnToggleScan.setText("Retomar");
                btnToggleScan.setIconResource(android.R.drawable.ic_media_play);
            }
        });

        btnCopy.setOnClickListener(v -> {
            String texto = txtResult.getText().toString();
            if (texto.isEmpty() || texto.equals("Escaneando...")) return;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Análise Nutricional", texto);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Copiado!", Toast.LENGTH_SHORT).show();
        });

        btnShare.setOnClickListener(v -> {
            String texto = txtResult.getText().toString();
            if (texto.isEmpty() || texto.equals("Escaneando...")) return;
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, texto);
            startActivity(Intent.createChooser(shareIntent, "Enviar via..."));
        });
    }

    private void setupZoom() {
        ScaleGestureDetector.SimpleOnScaleGestureListener listener = new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (camera != null) {
                    float currentZoomRatio = camera.getCameraInfo().getZoomState().getValue().getZoomRatio();
                    float delta = detector.getScaleFactor();
                    camera.getCameraControl().setZoomRatio(currentZoomRatio * delta);
                    return true;
                }
                return false;
            }
        };
        scaleGestureDetector = new ScaleGestureDetector(this, listener);
        previewView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void toggleFlash() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            isFlashEnabled = !isFlashEnabled;
            camera.getCameraControl().enableTorch(isFlashEnabled);
            btnFlash.setAlpha(isFlashEnabled ? 1.0f : 0.5f);
        }
    }

    private String analyzeNutrition(String rawText) {
        String text = rawText.toLowerCase();
        // Regex robusto para tabelas nutricionais
        Pattern pattern = Pattern.compile("(açúcar|açúcares)[^\\d]*(\\d+[\\.,]?\\d*)");
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            try {
                String valueStr = matcher.group(2).replace(",", ".");
                double sugarGrams = Double.parseDouble(valueStr);
                double teaspoons = sugarGrams / 4.0;

                String message = String.format("AÇÚCAR DETECTADO: %.1fg\n", sugarGrams);
                message += String.format("Equivale a: %.1f colheres de chá de açúcar puro.\n", teaspoons);

                message += "\n--- RECOMENDAÇÃO OMS ---";
                message += "\nIdeal: Máximo 25g (6 colheres) por dia.";

                if (sugarGrams >= 15.0) {
                    txtResult.setTextColor(Color.parseColor("#FF5252"));
                    message += "\n\n⚠️ ALERTA: Alto teor de açúcar!";
                } else {
                    txtResult.setTextColor(Color.parseColor("#8BC34A"));
                }

                return message;
            } catch (Exception e) {
                return "Erro nos valores.";
            }
        }
        txtResult.setTextColor(Color.parseColor("#E0E0E0"));
        return "Alinhe a linha de 'Açúcares' no quadro.\n\n" + rawText;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720)) // Resolução HD para precisão
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(previewView.getDisplay().getRotation())
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> cropAndRecognizeText(image));

                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
            } catch (Exception e) { Log.e("CameraX", "Erro", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void cropAndRecognizeText(ImageProxy imageProxy) {
        if (!isScanning || previewView.getWidth() == 0) {
            imageProxy.close();
            return;
        }

        Image imageInput = imageProxy.getImage();
        if (imageInput == null) { imageProxy.close(); return; }

        try {
            Bitmap bitmapOriginal = yuvToBitmap(imageInput);
            Matrix matrix = new Matrix();
            matrix.postRotate(imageProxy.getImageInfo().getRotationDegrees());
            Bitmap bitmapRotated = Bitmap.createBitmap(bitmapOriginal, 0, 0, bitmapOriginal.getWidth(), bitmapOriginal.getHeight(), matrix, true);

            // --- MATEMÁTICA DE MAPEAMENTO VERTICAL (O CORAÇÃO DO PROBLEMA) ---
            float viewWidth = previewView.getWidth();
            float viewHeight = previewView.getHeight();
            float bitmapWidth = bitmapRotated.getWidth();
            float bitmapHeight = bitmapRotated.getHeight();

            // Calcula a escala baseada no SCALE_TYPE "FILL_CENTER" do PreviewView
            float scaleX = viewWidth / bitmapWidth;
            float scaleY = viewHeight / bitmapHeight;
            float scale = Math.max(scaleX, scaleY);

            // Calcula o deslocamento (o que sobra da imagem fora da tela)
            float offsetX = (viewWidth - (bitmapWidth * scale)) / 2f;
            float offsetY = (viewHeight - (bitmapHeight * scale)) / 2f;

            // Converte as coordenadas do quadro visual para coordenadas da imagem real
            int cropLeft = (int) ((focusAreaView.getLeft() - offsetX) / scale);
            int cropTop = (int) ((focusAreaView.getTop() - offsetY) / scale);
            int cropWidth = (int) (focusAreaView.getWidth() / scale);
            int cropHeight = (int) (focusAreaView.getHeight() / scale);

            // Garante que o recorte está dentro dos limites da imagem
            cropLeft = Math.max(0, cropLeft);
            cropTop = Math.max(0, cropTop);
            if (cropLeft + cropWidth > bitmapRotated.getWidth()) cropWidth = bitmapRotated.getWidth() - cropLeft;
            if (cropTop + cropHeight > bitmapRotated.getHeight()) cropHeight = bitmapRotated.getHeight() - cropTop;

            if (cropWidth <= 0 || cropHeight <= 0) {
                imageProxy.close();
                return;
            }

            Bitmap bitmapCropped = Bitmap.createBitmap(bitmapRotated, cropLeft, cropTop, cropWidth, cropHeight);

            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(InputImage.fromBitmap(bitmapCropped, 0))
                    .addOnSuccessListener(visionText -> {
                        String texto = visionText.getText().trim();
                        if (!texto.isEmpty()) {
                            txtResult.setText(analyzeNutrition(texto));
                        }
                    })
                    .addOnCompleteListener(task -> {
                        imageProxy.close();
                        bitmapOriginal.recycle();
                        bitmapRotated.recycle();
                        bitmapCropped.recycle();
                    });

        } catch (Exception e) { imageProxy.close(); }
    }

    private Bitmap yuvToBitmap(Image image) {
        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();
        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 100, out);
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size());
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() { super.onDestroy(); cameraExecutor.shutdown(); }
}