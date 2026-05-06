package com.my.application;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

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
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

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
        if (!isScanning) {
            imageProxy.close();
            return;
        }

        Image mediaImage = imageProxy.getImage();
        if (mediaImage != null) {
            try {
                InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());

                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        .process(image)
                        .addOnSuccessListener(visionText -> {

                            android.graphics.Rect sugarBox = null;
                            
                            // PASSO 1: Encontrar ONDE a palavra Açúcar está na tela e pegar o "retângulo" dela
                            Pattern sugarPattern = Pattern.compile("a[cç][uú]car(?:es)?");

                            for (com.google.mlkit.vision.text.Text.TextBlock block : visionText.getTextBlocks()) {
                                for (com.google.mlkit.vision.text.Text.Line line : block.getLines()) {
                                    String textoLinha = line.getText().toLowerCase();

                                    // Usando Regex no lugar do "contains" para não perder nenhuma variação de acento!
                                    if (sugarPattern.matcher(textoLinha).find()) {
                                        sugarBox = line.getBoundingBox();
                                        break;
                                    }
                                }
                                if (sugarBox != null) break;
                            }

                            // PASSO 2: Procurar o número que está alinhado horizontalmente na MESMA ALTURA (Eixo Y)
                            if (sugarBox != null) {
                                int alvoY = sugarBox.centerY();
                                String melhorNumero = "";
                                int menorDistancia = Integer.MAX_VALUE;

                                for (com.google.mlkit.vision.text.Text.TextBlock block : visionText.getTextBlocks()) {
                                    for (com.google.mlkit.vision.text.Text.Line line : block.getLines()) {
                                        android.graphics.Rect numBox = line.getBoundingBox();
                                        if (numBox != null) {
                                            Matcher m = Pattern.compile("(\\d+[.,]?\\d*)").matcher(line.getText());
                                            if (m.find()) {
                                                int diferencaY = Math.abs(numBox.centerY() - alvoY);

                                                // REGRA: O número precisa estar à direita, e na mesma linha
                                                if (numBox.left > sugarBox.left && diferencaY < menorDistancia && diferencaY < (sugarBox.height() * 2)) {
                                                    menorDistancia = diferencaY;
                                                    melhorNumero = m.group(1);
                                                }
                                            }
                                        }
                                    }
                                }

                                if (!melhorNumero.isEmpty()) {
                                    txtResult.setText(buildNutritionMessage(melhorNumero));
                                } else {
                                    txtResult.setTextColor(Color.parseColor("#E0E0E0"));
                                    txtResult.setText("Achei a palavra Açúcares, mas o número na frente sumiu. Aproxime mais.");
                                }
                            } else {
                                txtResult.setTextColor(Color.parseColor("#E0E0E0"));
                                txtResult.setText("Procurando a palavra Açúcares na tabela...");
                            }
                        })
                        .addOnCompleteListener(task -> imageProxy.close());
            } catch (Exception e) {
                imageProxy.close();
            }
        } else {
            imageProxy.close();
        }
    }

    // Método focado em exibir a mensagem, recebendo o número já limpo
    private String buildNutritionMessage(String valueStr) {
        try {
            String cleanValue = valueStr.replace(",", ".");
            double sugarGrams = Double.parseDouble(cleanValue);
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

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}