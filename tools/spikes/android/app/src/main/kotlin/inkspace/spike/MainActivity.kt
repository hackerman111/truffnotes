package inkspace.spike

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import inkspace.spike.model.SpikeMetrics
import inkspace.spike.pdf.PdfiumSpikeAdapter
import inkspace.spike.surface.SpikeSurfaceView
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var surfaceView: SpikeSurfaceView
    private lateinit var pdfAdapter: PdfiumSpikeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pdfAdapter = PdfiumSpikeAdapter(this)

        setContent {
            SpikeScreen(
                onSurfaceViewCreated = { surfaceView = it },
                onClearClicked = { surfaceView.clearCanvas() },
                onExportPdfClicked = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            val pdfFile = File(cacheDir, "spike_vector_sample.pdf")
                            pdfAdapter.generateVectorSamplePdf(pdfFile)
                            Toast.makeText(this, "Vector PDF generated: ${pdfFile.length()} bytes", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "PDF export failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun SpikeScreen(
    onSurfaceViewCreated: (SpikeSurfaceView) -> Unit,
    onClearClicked: () -> Unit,
    onExportPdfClicked: () -> Unit
) {
    var metrics by remember { mutableStateOf(SpikeMetrics()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
    ) {
        // Header & Live Metrics Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Truffnotes / Inkspace Android Spike (F00)",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF0D47A1)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Canvas Post: ${"%.1f".format(metrics.canvasPostLatencyMs)} ms",
                        fontSize = 13.sp,
                        color = if (metrics.canvasPostLatencyMs <= 8.0f) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                    Text(
                        text = "Frame: ${"%.1f".format(metrics.frameDurationMs)} ms",
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Strokes: ${metrics.committedStrokeCount}",
                        fontSize = 13.sp
                    )
                    Text(
                        text = "PSS: ${metrics.pssMemoryMib} MiB",
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Epoch: ${metrics.surfaceEpoch}",
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Button(onClick = onClearClicked) {
                        Text("Clear Canvas")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onExportPdfClicked) {
                        Text("Export Vector PDF")
                    }
                }
            }
        }

        // SurfaceView host
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.0f)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .background(Color.White)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SpikeSurfaceView(context).apply {
                        onMetricsUpdated = { newMetrics ->
                            metrics = newMetrics
                        }
                        onSurfaceViewCreated(this)
                    }
                }
            )
        }
    }
}
