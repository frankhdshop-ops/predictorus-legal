package com.predictorus.app

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Mismos colores que css/contenedor.css y css/perfil.css
private val Fondo = Color(0xFF0A0F1F)
private val Tarjeta = Color(0xFF121A2E)
private val BordeTarjeta = Color(0xFF22304C)
private val Acento = Color(0xFFF2A93B)
private val AcentoOscuro = Color(0xFF14A89A)
private val Texto = Color(0xFFF5F7FA)
private val TextoSuave = Color(0xFF8A96B3)
private val FondoCampo = Color(0xFF0D1425)
private val Rojo = Color(0xFFE15555)
private val TextoSobreAcento = Color(0xFF1A1204)

private const val NOMBRE_CARGANDO = "Cargando…"

/** Estado del perfil; MainActivity lo llena con los datos de Firestore. */
// Correo al que llegan los reportes de problemas ("Reportar un problema" en el Perfil).
// Mientras esté vacío, tocar esa opción solo avisa que todavía no está configurada.
const val CORREO_SOPORTE = ""

class PerfilEstado {
    var nombre by mutableStateOf(NOMBRE_CARGANDO)
    var correo by mutableStateOf("")
    var fotoBase64 by mutableStateOf<String?>(null)
    var miembroDesde by mutableStateOf<String?>(null)
    var necesitaReconectar by mutableStateOf(false)
    var mensajeReconexion by mutableStateOf("")
    var eliminando by mutableStateOf(false)
    var sesionNativa by mutableStateOf(true)
    var diagnostico by mutableStateOf("")
    var probandoConexion by mutableStateOf(false)
    var errorCarga by mutableStateOf<String?>(null)

    fun reiniciar() {
        nombre = NOMBRE_CARGANDO
        correo = ""
        fotoBase64 = null
        miembroDesde = null
        necesitaReconectar = false
        mensajeReconexion = ""
        eliminando = false
        errorCarga = null
        // Resultado de la prueba de conexión de la sesión anterior
        sesionNativa = true
        diagnostico = ""
        probandoConexion = false
    }
}

@Composable
fun PerfilScreen(
    estado: PerfilEstado,
    alGuardarNombre: (String) -> Unit,
    alAbrirAcceso: (String) -> Unit,
    alCerrarSesion: () -> Unit,
    alReconectar: () -> Unit,
    alCambiarFoto: () -> Unit,
    alEliminarCuenta: () -> Unit,
    alProbarConexion: () -> Unit,
    alCopiarDiagnostico: () -> Unit
) {
    var editandoNombre by remember { mutableStateOf(false) }
    var confirmandoEliminar by remember { mutableStateOf(false) }

    TemaApp {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Fondo)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 20.dp)
        ) {
            if (estado.necesitaReconectar) {
                TarjetaReconectar(estado.mensajeReconexion, alReconectar)
                Spacer(Modifier.height(18.dp))
            }

            Cabecera(estado, alEditar = { editandoNombre = true }, alCambiarFoto = alCambiarFoto)

            // Si algo falló al leer los datos, se muestra el motivo real (sirve para diagnosticar)
            val errorCarga = estado.errorCarga
            if (errorCarga != null) {
                Text(
                    text = errorCarga,
                    color = Rojo,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            TituloSeccion("Accesos", primero = true)
            FilaAcceso(R.drawable.ic_ranking, "Ver ranking", "Tu posición en las ligas favoritas") {
                alAbrirAcceso("ranking")
            }
            FilaAcceso(R.drawable.ic_historial, "Ver historial completo", "Todas tus predicciones, con resultado real") {
                alAbrirAcceso("historial")
            }
            FilaAcceso(R.drawable.ic_mis_torneos, "Ver mis torneos", "Los que creaste o donde participas") {
                alAbrirAcceso("mis_torneos")
            }
            FilaAcceso(R.drawable.ic_ligas, "Ver mis ligas favoritas", "Marca o quita ligas, se guarda solo") {
                alAbrirAcceso("ligas_favoritas")
            }

            TituloSeccion("Ayuda y legal")
            FilaAcceso(R.drawable.ic_candado, "Política de privacidad", "Cómo tratamos tus datos") {
                alAbrirAcceso("privacidad")
            }
            FilaAcceso(R.drawable.ic_documento, "Términos y condiciones", "Las reglas para usar la app") {
                alAbrirAcceso("terminos")
            }
            FilaAcceso(R.drawable.ic_alerta, "Reportar un problema", "Escríbenos si algo no funciona") {
                alAbrirAcceso("reportar")
            }

            TituloSeccion("Pruebas")
            Text(
                text = if (estado.sesionNativa) "Sesión nativa: activa ✓" else "Sesión nativa: cerrada ✗ (usa Reconectar con Google)",
                color = if (estado.sesionNativa) TextoSuave else Rojo,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            // Prueba de conexión: mide la red, la clave de API y una lectura real de Firestore
            Spacer(Modifier.height(10.dp))
            val formaPrueba = RoundedCornerShape(12.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(formaPrueba)
                    .border(1.dp, Acento.copy(alpha = 0.5f), formaPrueba)
                    .clickable(enabled = !estado.probandoConexion) { alProbarConexion() }
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = if (estado.probandoConexion) "Probando…" else "Probar conexión",
                    color = Acento,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (estado.diagnostico.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .clip(formaPrueba)
                        .background(Tarjeta)
                        .border(1.dp, BordeTarjeta, formaPrueba)
                        .padding(12.dp)
                ) {
                    Text(
                        text = estado.diagnostico,
                        color = TextoSuave,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    if (!estado.probandoConexion) {
                        Text(
                            text = "Copiar resultado",
                            color = Acento,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(top = 10.dp)
                                .clickable { alCopiarDiagnostico() }
                        )
                    }
                }
            }

            TituloSeccion("Cuenta")
            BotonCerrarSesion(alCerrarSesion)
            Spacer(Modifier.height(14.dp))
            ZonaRiesgo(eliminando = estado.eliminando) { confirmandoEliminar = true }

            Spacer(Modifier.height(28.dp))
        }

        if (confirmandoEliminar) {
            DialogoEliminar(
                alCerrar = { confirmandoEliminar = false },
                alConfirmar = {
                    confirmandoEliminar = false
                    alEliminarCuenta()
                }
            )
        }

        if (editandoNombre) {
            DialogoNombre(
                nombreActual = if (estado.nombre == NOMBRE_CARGANDO) "" else estado.nombre,
                alCerrar = { editandoNombre = false },
                alConfirmar = alGuardarNombre
            )
        }
    }
}

// ---------- Cabecera ----------

@Composable
private fun Cabecera(estado: PerfilEstado, alEditar: () -> Unit, alCambiarFoto: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(estado.nombre, estado.fotoBase64, alCambiarFoto)
        Spacer(Modifier.width(14.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = estado.nombre,
                    color = Texto,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    painter = painterResource(R.drawable.ic_editar),
                    contentDescription = "Editar nombre",
                    tint = TextoSuave,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable { alEditar() }
                        .padding(7.dp)
                )
            }
            if (estado.correo.isNotEmpty()) {
                Text(
                    text = estado.correo,
                    color = TextoSuave,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            val desde = estado.miembroDesde
            if (desde != null) {
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Text("Miembro desde ", color = TextoSuave, fontSize = 11.sp)
                    Text(desde, color = Acento, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun Avatar(nombre: String, foto: String?, alTocar: () -> Unit) {
    val imagen = remember(foto) { decodificarFoto(foto) }
    val inicial = if (nombre.isBlank() || nombre == NOMBRE_CARGANDO) "" else nombre.trim().first().uppercase()

    Box(
        modifier = Modifier
            .size(62.dp)
            .clickable { alTocar() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Acento, AcentoOscuro)))
        ) {
            if (imagen != null) {
                Image(
                    bitmap = imagen,
                    contentDescription = "Foto de perfil",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(text = inicial, color = TextoSobreAcento, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Insignia con el lápiz: indica que la foto se puede cambiar
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(2.dp, 2.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(Acento)
                .border(2.dp, Fondo, CircleShape)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_editar),
                contentDescription = "Cambiar foto",
                tint = TextoSobreAcento,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

internal fun decodificarFoto(dataUrl: String?): ImageBitmap? {
    if (dataUrl.isNullOrEmpty()) return null
    return try {
        val base64 = dataUrl.substringAfter("base64,", dataUrl)
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

// ---------- Resumen ----------

// ---------- Accesos y cuenta ----------

@Composable
private fun TituloSeccion(texto: String, primero: Boolean = false) {
    Text(
        text = texto.uppercase(),
        color = TextoSuave,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(top = if (primero) 22.dp else 26.dp, bottom = 10.dp)
    )
}

@Composable
private fun FilaAcceso(icono: Int, titulo: String, subtitulo: String, alTocar: () -> Unit) {
    val forma = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(forma)
            .background(Tarjeta)
            .border(1.dp, BordeTarjeta, forma)
            .clickable { alTocar() }
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Acento.copy(alpha = 0.12f))
        ) {
            Icon(
                painter = painterResource(icono),
                contentDescription = null,
                tint = Acento,
                modifier = Modifier.size(15.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(titulo, color = Texto, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitulo, color = TextoSuave, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron),
            contentDescription = null,
            tint = TextoSuave,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun FilaInterruptor(
    titulo: String,
    subtitulo: String,
    activo: Boolean,
    alCambiar: (Boolean) -> Unit
) {
    val forma = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(forma)
            .background(Tarjeta)
            .border(1.dp, BordeTarjeta, forma)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(titulo, color = Texto, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitulo, color = TextoSuave, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = activo,
            onCheckedChange = alCambiar,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextoSobreAcento,
                checkedTrackColor = Acento,
                uncheckedThumbColor = TextoSuave,
                uncheckedTrackColor = FondoCampo,
                uncheckedBorderColor = BordeTarjeta
            )
        )
    }
}

@Composable
private fun BotonCerrarSesion(alTocar: () -> Unit) {
    val forma = RoundedCornerShape(12.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(forma)
            .background(Rojo.copy(alpha = 0.08f))
            .border(1.dp, Rojo.copy(alpha = 0.4f), forma)
            .clickable { alTocar() }
            .padding(13.dp)
    ) {
        Text("Cerrar sesión", color = Rojo, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------- Eliminar cuenta ----------

@Composable
private fun ZonaRiesgo(eliminando: Boolean, alTocar: () -> Unit) {
    val forma = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(forma)
            .background(Rojo.copy(alpha = 0.06f))
            .border(1.dp, Rojo.copy(alpha = 0.3f), forma)
            .padding(horizontal = 15.dp, vertical = 13.dp)
    ) {
        Text("Eliminar cuenta", color = Rojo, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(
            "Esta acción es permanente y no se puede deshacer.",
            color = TextoSuave,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp)
        )

        val formaBoton = RoundedCornerShape(10.dp)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .clip(formaBoton)
                .background(Rojo.copy(alpha = 0.1f))
                .border(1.dp, Rojo.copy(alpha = 0.4f), formaBoton)
                .clickable(enabled = !eliminando) { alTocar() }
                .padding(10.dp)
        ) {
            Text(
                text = if (eliminando) "Eliminando…" else "Eliminar mi cuenta",
                color = Rojo,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DialogoEliminar(alCerrar: () -> Unit, alConfirmar: () -> Unit) {
    AlertDialog(
        onDismissRequest = alCerrar,
        containerColor = Tarjeta,
        title = { Text("Eliminar cuenta", color = Texto, fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                "¿Seguro que quieres eliminar tu cuenta? Esto es permanente y no se puede deshacer.\n\n" +
                    "Se borrarán tu perfil, tus predicciones, tus puntos en los rankings y tu participación " +
                    "en torneos. Los torneos que creaste se eliminarán para todos sus participantes.",
                color = TextoSuave,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = alConfirmar) {
                Text("Eliminar", color = Rojo, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = alCerrar) {
                Text("Cancelar", color = TextoSuave)
            }
        }
    )
}

// ---------- Reconexión de la sesión nativa ----------

@Composable
private fun TarjetaReconectar(mensaje: String, alReconectar: () -> Unit) {
    val forma = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(forma)
            .background(Tarjeta)
            .border(1.dp, Acento.copy(alpha = 0.5f), forma)
            .padding(16.dp)
    ) {
        Text(
            text = "Para cargar tu perfil necesitas volver a conectar tu cuenta de Google.",
            color = Texto,
            fontSize = 13.sp
        )
        if (mensaje.isNotEmpty()) {
            Text(mensaje, color = Rojo, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Acento)
                .clickable { alReconectar() }
                .padding(13.dp)
        ) {
            Text("Reconectar con Google", color = TextoSobreAcento, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- Editar nombre ----------

@Composable
private fun DialogoNombre(
    nombreActual: String,
    alCerrar: () -> Unit,
    alConfirmar: (String) -> Unit
) {
    var texto by remember { mutableStateOf(nombreActual) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = alCerrar,
        containerColor = Tarjeta,
        title = { Text("Editar nombre", color = Texto, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = texto,
                    onValueChange = {
                        texto = it
                        error = ""
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Texto,
                        unfocusedTextColor = Texto,
                        focusedContainerColor = FondoCampo,
                        unfocusedContainerColor = FondoCampo,
                        cursorColor = Acento,
                        focusedBorderColor = Acento,
                        unfocusedBorderColor = BordeTarjeta
                    )
                )
                if (error.isNotEmpty()) {
                    Text(error, color = Rojo, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val limpio = texto.trim()
                when {
                    limpio.isEmpty() -> error = "El nombre no puede quedar vacío."
                    limpio == nombreActual -> alCerrar()
                    else -> {
                        alConfirmar(limpio)
                        alCerrar()
                    }
                }
            }) {
                Text("Guardar", color = Acento, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = alCerrar) {
                Text("Cancelar", color = TextoSuave)
            }
        }
    )
}
