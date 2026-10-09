package com.predictorus.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Toast
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Calendar

/**
 * Actividad única: todas las pantallas de la app son nativas (Jetpack Compose).
 */
class MainActivity : ComponentActivity() {

    companion object {
        // Alto de la barra inferior nativa
        private const val ALTO_NAV_DP = 64

        // Si el arranque tarda demasiado, el splash se cierra solo pasado este tiempo.
        private const val SPLASH_MAXIMO_MS = 8000L
    }

    private lateinit var loginView: ComposeView
    private lateinit var datosView: ComposeView
    private lateinit var navView: ComposeView
    private lateinit var perfilView: ComposeView
    private lateinit var globalView: ComposeView
    private lateinit var torneosView: ComposeView
    private lateinit var torneoDetalleView: ComposeView
    private lateinit var crearTorneoView: ComposeView
    private lateinit var misTorneosView: ComposeView
    private lateinit var historialView: ComposeView
    private lateinit var rankingView: ComposeView
    private lateinit var burbujaView: ComposeView
    private lateinit var ligasFavoritasView: ComposeView
    private lateinit var credentialManager: CredentialManager

    private val manejador = Handler(Looper.getMainLooper())
    private val loginEstado = LoginEstado()
    private val datosEstado = DatosEstado()
    private val perfilEstado = PerfilEstado()

    // Global
    private val globalEstado = GlobalEstado()
    private val escuchaPartidos = EscuchaPartidos()
    private var recargaPrediccionesPendiente = false
    private val rankingsAsegurados = HashSet<String>()
    private var diaGlobal = LocalDate.now().toString()
    private var favoritasConocidas = false
    private var onboardingCerrado = false

    // Torneos
    private val torneosEstado = TorneosEstado()
    private val torneosControlador by lazy {
        TorneosControlador(torneosEstado, lifecycleScope) { texto ->
            Toast.makeText(this, texto, Toast.LENGTH_LONG).show()
        }
    }

    // Detalle de un torneo (nativo): cubre la lista de Torneos
    private val torneoDetalleEstado = TorneoDetalleEstado()
    private val torneoDetalleControlador by lazy {
        TorneoDetalleControlador(
            torneoDetalleEstado,
            lifecycleScope,
            { texto -> Toast.makeText(this, texto, Toast.LENGTH_LONG).show() },
            { torneosControlador.marcarObsoleto() },
            {
                // Torneo eliminado: se oculta el detalle y la lista se recarga sin él
                actualizarTorneoDetalle()
                torneosControlador.reiniciarLista()
                volverAMisTorneosSiVenia()
            }
        )
    }

    // Crear torneo (nativo): formulario de 5 pasos que cubre la lista de Torneos
    private val crearTorneoEstado = CrearTorneoEstado()
    private val crearTorneoControlador by lazy {
        CrearTorneoControlador(crearTorneoEstado, lifecycleScope) { torneosControlador.reiniciarLista() }
    }

    // Mis torneos (nativo): se abre desde Perfil > Accesos y cubre el Perfil
    private val misTorneosEstado = MisTorneosEstado()
    private val misTorneosControlador by lazy { MisTorneosControlador(misTorneosEstado, lifecycleScope) }

    // El detalle del torneo se abrió desde Mis torneos: al cerrarlo se vuelve allí y no a la lista
    private var volverAMisTorneos = false

    // Historial (nativo): desde Perfil > Accesos (el de Global) o desde el detalle de un torneo
    private val historialEstado = HistorialEstado()
    private val historialControlador by lazy { HistorialControlador(historialEstado, lifecycleScope) }

    // Pestaña sobre la que se abrió el Historial: "perfil" o "torneos"
    private var historialOrigenTab = "perfil"

    // Mis ligas favoritas (nativo): desde Perfil > Accesos o desde el lápiz de Global
    private val ligasFavoritasEstado = LigasFavoritasEstado()
    private val ligasFavoritasControlador by lazy {
        LigasFavoritasControlador(
            ligasFavoritasEstado,
            lifecycleScope,
            { globalEstado.favoritas },
            ::cambiarFavoritasGlobal,
            { nuevas -> globalEstado.favoritas = nuevas },
            { texto -> Toast.makeText(this, texto, Toast.LENGTH_LONG).show() }
        )
    }

    // Pestaña sobre la que se abrió Mis ligas favoritas: "perfil" o "global"
    private var ligasOrigenTab = "perfil"

    // Se envió un enlace al correo para confirmar la identidad antes de eliminar la cuenta
    private var confirmandoIdentidad = false

    // Burbuja de estadísticas de un usuario: se abre al tocar una fila de cualquier ranking
    private val burbujaEstado = BurbujaEstado()
    private val burbujaControlador by lazy { BurbujaControlador(burbujaEstado, lifecycleScope) }

    // Ranking de Global (nativo): se abre desde Perfil > Accesos y cubre el Perfil
    private val rankingEstado = RankingEstado()
    private val rankingControlador by lazy {
        RankingControlador(
            rankingEstado,
            lifecycleScope,
            { globalEstado.favoritas },
            { nuevas ->
                val antes = globalEstado.favoritas
                globalEstado.favoritas = nuevas
                // Igual que la web: la primera favorita enciende el filtro de favoritas (nunca lo apaga)
                if (antes.isEmpty() && nuevas.isNotEmpty() && !globalEstado.soloFavoritos) {
                    globalEstado.soloFavoritos = true
                }
            },
            // Al abrir el ranking se relee la lista de favoritas (sin tocar el filtro de Global)
            { nuevas -> globalEstado.favoritas = nuevas },
            { texto -> Toast.makeText(this, texto, Toast.LENGTH_LONG).show() }
        )
    }

    // Pestaña marcada en la barra inferior
    private var tabActual by mutableStateOf("global")

    // Selector de fotos del sistema (no necesita permisos)
    private val selectorFoto = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) cambiarFoto(uri)
    }

    @Volatile
    private var splashVisible = true
    private var primerResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { splashVisible }
        super.onCreate(savedInstanceState)

        manejador.postDelayed({ splashVisible = false }, SPLASH_MAXIMO_MS)

        credentialManager = CredentialManager.create(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val raiz = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.fondo)) }
        setContentView(raiz)
        WindowCompat.getInsetsController(window, raiz).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // La web no sabe de barras del sistema ni de teclado: se reserva ese espacio aquí.
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { vista, insets ->
            val tipos = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
            val m = insets.getInsets(tipos)
            vista.setPadding(m.left, m.top, m.right, m.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // Barra inferior nativa: solo se ve en la página principal.
        navView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                NavBar(
                    tabActual = tabActual,
                    alElegir = ::elegirTab
                )
            }
        }
        raiz.addView(
            navView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (ALTO_NAV_DP * resources.displayMetrics.density).toInt(),
                Gravity.BOTTOM
            )
        )

        // Perfil nativo: cubre el contenido de la web cuando la pestaña Perfil está activa
        // (deja libre la barra inferior).
        perfilView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                PerfilScreen(
                    estado = perfilEstado,
                    alGuardarNombre = ::guardarNombre,
                    alAbrirAcceso = ::abrirAcceso,
                    alCerrarSesion = ::cerrarSesion,
                    alReconectar = ::reconectarNativo,
                    alCambiarFoto = ::elegirFoto,
                    alEliminarCuenta = ::eliminarCuenta,
                    alProbarConexion = ::probarConexion,
                    alCopiarDiagnostico = ::copiarDiagnostico
                )
            }
        }
        raiz.addView(
            perfilView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = (ALTO_NAV_DP * resources.displayMetrics.density).toInt() }
        )

        // Global nativo (prueba): cubre el contenido de la web cuando la pestaña Global
        // está activa y el interruptor de Perfil está encendido.
        globalView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                GlobalScreen(
                    estado = globalEstado,
                    alCambiarModo = ::globalCambiarModo,
                    alElegirFecha = ::globalElegirFecha,
                    alAlternarFavoritos = ::globalAlternarFavoritos,
                    alAbrirMisLigas = { abrirLigasFavoritas("global") },
                    alGuardado = ::alGuardarPredicciones,
                    alLigaPredicha = ::marcarLigaFavoritaPorPrediccion,
                    alTerminarOnboarding = ::terminarOnboarding
                )
            }
        }
        raiz.addView(
            globalView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = (ALTO_NAV_DP * resources.displayMetrics.density).toInt() }
        )

        // Torneos nativo (prueba): cubre el contenido de la web cuando la pestaña Torneos
        // está activa y el interruptor de Perfil está encendido.
        torneosView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                TorneosScreen(
                    estado = torneosEstado,
                    alCambiarModo = ::globalCambiarModo,
                    alBuscar = { torneosControlador.buscarPorCodigo() },
                    alVolverDeBusqueda = { torneosControlador.volverALista() },
                    alAbrirTorneo = ::abrirTorneoNativo,
                    alCrear = ::abrirCrearTorneo,
                    alCargarMas = { torneosControlador.cargarSiguientePagina() },
                    alAplicarFiltros = { region, premios, cupos ->
                        torneosControlador.aplicarFiltros(region, premios, cupos)
                    },
                    alReintentar = { torneosControlador.reiniciarLista() }
                )
            }
        }
        raiz.addView(
            torneosView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = (ALTO_NAV_DP * resources.displayMetrics.density).toInt() }
        )

        // Detalle de un torneo (nativo): se ve cuando se toca un torneo de la lista nativa.
        torneoDetalleView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                TorneoDetalleScreen(
                    estado = torneoDetalleEstado,
                    alVolver = ::cerrarTorneoNativo,
                    alCompartir = ::compartirTorneo,
                    alUnirse = { torneoDetalleControlador.unirse() },
                    alAbrirRanking = { torneoDetalleControlador.abrirRanking() },
                    alAbrirHistorial = ::abrirHistorialDelTorneo,
                    alGuardado = { exacto, quiniela ->
                        torneoDetalleControlador.alGuardarPredicciones(exacto, quiniela)
                    },
                    alPedirSalir = { torneoDetalleEstado.confirmandoSalir = true },
                    alConfirmarSalir = {
                        torneoDetalleControlador.salir()
                    },
                    alCancelarSalir = { torneoDetalleEstado.confirmandoSalir = false },
                    alPedirEliminar = { torneoDetalleControlador.pedirEliminar() },
                    alConfirmarEliminar = { torneoDetalleControlador.eliminar() },
                    alCancelarEliminar = { torneoDetalleControlador.cancelarEliminar() },
                    alVolverDelRanking = { torneoDetalleControlador.volverDelRanking() },
                    alCargarMasRanking = { torneoDetalleControlador.cargarMasRanking() },
                    alMostrarVecinos = { torneoDetalleControlador.mostrarVecinos() },
                    alOcultarVecinos = { torneoDetalleControlador.ocultarVecinos() },
                    alTocarFilaRanking = ::abrirBurbujaTorneo,
                    alReintentar = { torneoDetalleControlador.recargar() }
                )
            }
        }
        raiz.addView(
            torneoDetalleView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = (ALTO_NAV_DP * resources.displayMetrics.density).toInt() }
        )

        // Crear torneo (nativo): se ve al tocar "+ Crear" en la lista nativa de Torneos.
        crearTorneoView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                CrearTorneoScreen(
                    estado = crearTorneoEstado,
                    controlador = crearTorneoControlador,
                    alCerrar = {
                        crearTorneoControlador.cerrar()
                        actualizarCrearTorneo()
                    },
                    alCompartir = ::compartirTexto
                )
            }
        }
        raiz.addView(
            crearTorneoView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = (ALTO_NAV_DP * resources.displayMetrics.density).toInt() }
        )

        // Mis torneos (nativo): se ve al tocar "Ver mis torneos" en Perfil > Accesos.
        misTorneosView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                MisTorneosScreen(
                    estado = misTorneosEstado,
                    alCambiarModo = ::globalCambiarModo,
                    alVolver = ::cerrarMisTorneos,
                    alAbrirTorneo = ::abrirTorneoDesdeMisTorneos,
                    alAplicarFiltros = { rol, region -> misTorneosControlador.aplicarFiltros(rol, region) },
                    alReintentar = { misTorneosControlador.recargar() }
                )
            }
        }
        raiz.addView(
            misTorneosView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = (ALTO_NAV_DP * resources.displayMetrics.density).toInt() }
        )

        // Historial (nativo): cubre la pantalla desde la que se abrió (Perfil o detalle de un torneo).
        historialView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                HistorialScreen(
                    estado = historialEstado,
                    alCambiarModo = ::globalCambiarModo,
                    alVolver = ::cerrarHistorial,
                    alAplicarFiltros = { orden, resultado, liga, equipo ->
                        historialControlador.aplicarFiltros(orden, resultado, liga, equipo)
                    },
                    alCargarMas = { historialControlador.cargarMas() },
                    alReintentar = { historialControlador.reintentar() }
                )
            }
        }
        raiz.addView(
            historialView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = (ALTO_NAV_DP * resources.displayMetrics.density).toInt() }
        )

        // Ranking de Global (nativo): se ve al tocar "Ver ranking" en Perfil > Accesos.
        rankingView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                RankingScreen(
                    estado = rankingEstado,
                    favoritas = globalEstado.favoritas,
                    alCambiarModo = ::globalCambiarModo,
                    alVolver = ::cerrarRanking,
                    alElegirLiga = { id -> rankingControlador.abrirDetalle(id) },
                    alAlternarFavorita = { id -> rankingControlador.alternarFavorita(id) },
                    alMostrarVecinos = { rankingControlador.mostrarVecinos() },
                    alOcultarVecinos = { rankingControlador.ocultarVecinos() },
                    alMostrarMenu = { rankingControlador.volverAlMenu() },
                    alTocarFila = ::abrirBurbujaRankingGlobal,
                    alAplicarFiltros = { liga, solo -> rankingControlador.aplicarFiltros(liga, solo) },
                    alReintentar = { rankingControlador.reintentar() }
                )
            }
        }
        raiz.addView(
            rankingView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = (ALTO_NAV_DP * resources.displayMetrics.density).toInt() }
        )

        // Mis ligas favoritas (nativo): cubre Perfil o Global, según desde dónde se abrió.
        ligasFavoritasView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                LigasFavoritasScreen(
                    estado = ligasFavoritasEstado,
                    favoritas = globalEstado.favoritas,
                    alCambiarModo = ::globalCambiarModo,
                    alVolver = ::cerrarLigasFavoritas,
                    alAlternar = { id -> ligasFavoritasControlador.alternar(id) }
                )
            }
        }
        raiz.addView(
            ligasFavoritasView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = (ALTO_NAV_DP * resources.displayMetrics.density).toInt() }
        )

        // Burbuja de estadísticas: solo contiene un diálogo, así que no ocupa lugar en pantalla.
        burbujaView = ComposeView(this).apply {
            setContent {
                TemaApp {
                    BurbujaEstadisticas(burbujaEstado) { burbujaControlador.cerrar() }
                }
            }
        }
        raiz.addView(burbujaView, FrameLayout.LayoutParams(1, 1))

        // Pantalla de login: se muestra al arrancar si no hay sesión.
        loginView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                LoginScreen(
                    estado = loginEstado,
                    alTocarGoogle = ::entrarConGoogle,
                    alPedirLink = ::pedirLinkPorCorreo,
                    alConfirmarCorreoEnlace = ::confirmarCorreoEnlace,
                    alCancelarEnlace = { loginEstado.enlacePendiente = null }
                )
            }
        }
        raiz.addView(
            loginView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Pantalla de datos: la ve el usuario nuevo para elegir su nombre.
        datosView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                DatosScreen(
                    estado = datosEstado,
                    alContinuar = ::guardarDatos
                )
            }
        }
        raiz.addView(
            datosView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Botón atrás: en login y datos se sale de la app; en el resto se cierra lo que esté
        // abierto encima y, desde otra pestaña, se vuelve a Global.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (loginView.visibility == View.VISIBLE ||
                        datosView.visibility == View.VISIBLE
                    ) {
                        finish()
                        return
                    }
                    if (crearTorneoView.visibility == View.VISIBLE) {
                        crearTorneoControlador.atrasDelSistema()
                        actualizarCrearTorneo()
                        return
                    }
                    if (ligasFavoritasView.visibility == View.VISIBLE) {
                        cerrarLigasFavoritas()
                        return
                    }
                    if (rankingView.visibility == View.VISIBLE) {
                        rankingControlador.atras()
                        actualizarRanking()
                        return
                    }
                    if (historialView.visibility == View.VISIBLE) {
                        cerrarHistorial()
                        return
                    }
                    if (misTorneosView.visibility == View.VISIBLE) {
                        cerrarMisTorneos()
                        return
                    }
                    if (torneoDetalleView.visibility == View.VISIBLE) {
                        torneoDetalleControlador.atras()
                        actualizarTorneoDetalle()
                        volverAMisTorneosSiVenia()
                        return
                    }
                    if (navView.visibility == View.VISIBLE && tabActual != "global") {
                        elegirTab("global")
                    } else {
                        finish()
                    }
                }
            }
        )

        arrancar()
        procesarEnlace(intent)
    }

    // Un enlace de acceso por correo abre la app que ya estaba abierta
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        procesarEnlace(intent)
    }

    // ---------- Torneos nativo (prueba) ----------

    // Se ve cuando la pestaña activa es Torneos, no hay una página web encima y el interruptor está encendido.
    private fun actualizarTorneos() {
        val debeVerse = navView.visibility == View.VISIBLE &&
            tabActual == "torneos"
        val yaSeVeia = torneosView.visibility == View.VISIBLE

        torneosView.visibility = if (debeVerse) View.VISIBLE else View.GONE
        if (debeVerse && !yaSeVeia) {
            if (Sesion.usuario == null) {
                torneosEstado.error = "Reconecta tu cuenta de Google desde Perfil para ver Torneos nativo."
            } else {
                torneosControlador.reiniciarSiHaceFalta()
            }
        }
    }

    // El modo de juego es compartido con Global y con el resto de la web
    private fun torneosAlCambiarModo(modo: String) {
        misTorneosEstado.modo = modo
        historialControlador.cambiarModo(modo)
        rankingControlador.cambiarModo(modo)
        ligasFavoritasEstado.modo = modo
        if (torneosEstado.modo == modo) return
        torneosEstado.modo = modo
        if (torneosView.visibility == View.VISIBLE) {
            torneosControlador.alCambiarModo()
        } else {
            torneosEstado.busqueda = null
            torneosControlador.marcarObsoleto()
        }
    }

    // ---------- Mis torneos (nativo) ----------

    private fun abrirMisTorneos() {
        if (Sesion.usuario == null) {
            Toast.makeText(
                this,
                "Reconecta tu cuenta de Google desde Perfil para ver tus torneos.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        misTorneosControlador.abrir(torneosEstado.modo)
        actualizarMisTorneos()
    }

    private fun cerrarMisTorneos() {
        misTorneosControlador.cerrar()
        actualizarMisTorneos()
    }

    // Se ve mientras esté abierto, en la pestaña Perfil y sin una página web encima
    private fun actualizarMisTorneos() {
        if (misTorneosEstado.abierto && tabActual != "perfil") misTorneosControlador.cerrar()
        val debeVerse = misTorneosEstado.abierto &&
            navView.visibility == View.VISIBLE &&
            tabActual == "perfil"
        misTorneosView.visibility = if (debeVerse) View.VISIBLE else View.GONE
    }

    // El detalle vive en la pestaña Torneos: se cambia de pestaña y se abre ahí
    private fun abrirTorneoDesdeMisTorneos(torneoId: String) {
        misTorneosControlador.cerrar()
        elegirTab("torneos")
        abrirTorneoNativo(torneoId)
        volverAMisTorneos = true
    }

    // ---------- Detalle de un torneo (nativo) ----------

    private fun abrirTorneoNativo(torneoId: String) {
        torneoDetalleControlador.abrir(torneoId)
        actualizarTorneoDetalle()
    }

    private fun cerrarTorneoNativo() {
        torneoDetalleControlador.cerrar()
        actualizarTorneoDetalle()
        volverAMisTorneosSiVenia()
    }

    // Si el detalle ya se cerró y venía de Mis torneos, vuelve a esa página dentro del Perfil
    private fun volverAMisTorneosSiVenia() {
        if (!volverAMisTorneos || torneoDetalleEstado.abierto) return
        volverAMisTorneos = false
        elegirTab("perfil")
        abrirMisTorneos()
    }

    // Se ve mientras esté abierto, en la pestaña Torneos y sin una página web encima
    private fun actualizarTorneoDetalle() {
        if (torneoDetalleEstado.abierto && tabActual != "torneos") torneoDetalleControlador.cerrar()
        val debeVerse = torneoDetalleEstado.abierto &&
            navView.visibility == View.VISIBLE &&
            tabActual == "torneos"
        torneoDetalleView.visibility = if (debeVerse) View.VISIBLE else View.GONE
    }

    // Menú de compartir de Android con el mismo mensaje de invitación de la web
    private fun compartirTorneo() {
        val torneo = torneoDetalleEstado.torneo ?: return
        val codigo = torneo.codigo
        val texto = "Súmate a mi torneo \"${torneo.nombre}\" en Predictorus." +
            if (codigo.isNullOrEmpty()) "" else " Código: $codigo"
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, texto)
        }
        startActivity(Intent.createChooser(envio, null))
    }

    // ---------- Mis ligas favoritas (nativo) ----------

    private fun abrirLigasFavoritas(origen: String) {
        ligasOrigenTab = origen
        ligasFavoritasEstado.modo = globalEstado.modo
        ligasFavoritasControlador.abrir()
        actualizarLigasFavoritas()
    }

    private fun cerrarLigasFavoritas() {
        ligasFavoritasControlador.cerrar()
        actualizarLigasFavoritas()
    }

    // Se ve mientras esté abierta, sobre la pestaña desde la que se abrió
    private fun actualizarLigasFavoritas() {
        if (ligasFavoritasEstado.abierto && tabActual != ligasOrigenTab) ligasFavoritasControlador.cerrar()
        val debeVerse = ligasFavoritasEstado.abierto &&
            navView.visibility == View.VISIBLE &&
            tabActual == ligasOrigenTab
        ligasFavoritasView.visibility = if (debeVerse) View.VISIBLE else View.GONE
    }

    // Igual que la web: la primera favorita enciende el filtro "Favoritos" de Global (nunca lo apaga)
    private fun cambiarFavoritasGlobal(nuevas: List<String>) {
        val antes = globalEstado.favoritas
        globalEstado.favoritas = nuevas
        if (antes.isEmpty() && nuevas.isNotEmpty() && !globalEstado.soloFavoritos) {
            globalEstado.soloFavoritos = true
        }
    }

    // ---------- Favoritas automáticas ----------

    // Predecir un partido de una liga que no era favorita la vuelve favorita. No se enciende
    // el filtro "Favoritos" de Global: la persona estaba predeciendo, no filtrando.
    private fun marcarLigaFavoritaPorPrediccion(competenciaId: String) {
        val uid = Sesion.usuario?.uid ?: return
        if (competenciaId in globalEstado.favoritas) return
        globalEstado.favoritas = globalEstado.favoritas + competenciaId
        lifecycleScope.launch {
            try {
                GlobalRepositorio.marcarFavorita(uid, competenciaId, true)
            } catch (e: Exception) {
                // No se pudo guardar: la liga vuelve a quedar como no favorita
                globalEstado.favoritas = globalEstado.favoritas - competenciaId
            }
        }
    }

    // ---------- Burbuja de estadísticas ----------

    private fun abrirBurbujaRankingGlobal(fila: FilaRankingUI) {
        val competenciaId = rankingEstado.competenciaId ?: return
        burbujaControlador.abrir(
            AmbitoRanking(false, competenciaId, rankingEstado.modo == "quiniela", competenciaId),
            fila
        )
    }

    private fun abrirBurbujaTorneo(fila: FilaRankingUI) {
        val torneo = torneoDetalleEstado.torneo ?: return
        burbujaControlador.abrir(
            AmbitoRanking(true, torneo.id, torneo.tipoTorneo == "quiniela", torneo.competencias.firstOrNull()),
            fila
        )
    }

    // ---------- Ranking de Global (nativo) ----------

    private fun abrirRankingGlobal() {
        if (Sesion.usuario == null) {
            Toast.makeText(
                this,
                "Reconecta tu cuenta de Google desde Perfil para ver el ranking.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        rankingControlador.abrirMenu(globalEstado.modo)
        actualizarRanking()
    }

    private fun cerrarRanking() {
        rankingControlador.cerrar()
        actualizarRanking()
    }

    // Se ve mientras esté abierto, en la pestaña Perfil y sin una página web encima
    private fun actualizarRanking() {
        if (rankingEstado.abierto && tabActual != "perfil") rankingControlador.cerrar()
        val debeVerse = rankingEstado.abierto &&
            navView.visibility == View.VISIBLE &&
            tabActual == "perfil"
        rankingView.visibility = if (debeVerse) View.VISIBLE else View.GONE
    }

    // ---------- Historial (nativo) ----------

    private fun abrirHistorialGlobal() {
        if (Sesion.usuario == null) {
            Toast.makeText(
                this,
                "Reconecta tu cuenta de Google desde Perfil para ver tu historial.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        historialOrigenTab = "perfil"
        historialControlador.abrirGlobal(globalEstado.modo)
        actualizarHistorial()
    }

    // El Historial de un torneo muestra las predicciones propias de ese torneo
    private fun abrirHistorialDelTorneo() {
        val torneo = torneoDetalleEstado.torneo ?: return
        if (Sesion.usuario == null) {
            Toast.makeText(
                this,
                "Reconecta tu cuenta de Google desde Perfil para ver tu historial.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        historialOrigenTab = "torneos"
        historialControlador.abrirTorneo(torneo.id, torneo.nombre, torneo.tipoTorneo == "quiniela")
        actualizarHistorial()
    }

    private fun cerrarHistorial() {
        historialControlador.cerrar()
        actualizarHistorial()
    }

    // Se ve mientras esté abierto, sobre la pestaña desde la que se abrió y sin una página web encima
    private fun actualizarHistorial() {
        if (historialEstado.abierto && tabActual != historialOrigenTab) historialControlador.cerrar()
        val debeVerse = historialEstado.abierto &&
            navView.visibility == View.VISIBLE &&
            tabActual == historialOrigenTab
        historialView.visibility = if (debeVerse) View.VISIBLE else View.GONE
    }

    // Crear torneo nativo: el torneo nace con el modo de juego activo (igual que en la web)
    private fun abrirCrearTorneo() {
        if (Sesion.usuario == null) {
            Toast.makeText(
                this,
                "Reconecta tu cuenta de Google desde Perfil para crear un torneo.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        crearTorneoControlador.abrir(torneosEstado.modo)
        actualizarCrearTorneo()
    }

    // Se ve mientras esté abierto, en la pestaña Torneos y sin una página web encima
    private fun actualizarCrearTorneo() {
        if (crearTorneoEstado.abierto && tabActual != "torneos") crearTorneoControlador.cerrar()
        val debeVerse = crearTorneoEstado.abierto &&
            navView.visibility == View.VISIBLE &&
            tabActual == "torneos"
        crearTorneoView.visibility = if (debeVerse) View.VISIBLE else View.GONE
    }

    // Menú de compartir de Android con un texto ya armado
    private fun compartirTexto(texto: String) {
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, texto)
        }
        startActivity(Intent.createChooser(envio, null))
    }

    // ---------- Global nativo (prueba) ----------

    // Se ve cuando la pestaña activa es Global, no hay una página web encima y el interruptor está encendido.
    private fun actualizarGlobal() {
        val debeVerse = navView.visibility == View.VISIBLE &&
            tabActual == "global"
        val yaSeVeia = globalView.visibility == View.VISIBLE

        globalView.visibility = if (debeVerse) View.VISIBLE else View.GONE
        if (debeVerse && !yaSeVeia) {
            iniciarGlobal()
        } else if (!debeVerse && yaSeVeia) {
            escuchaPartidos.detener()
        }
    }

    // Cada vez que se entra a Global (o se vuelve de "Mis ligas") se relee la lista de favoritas
    // y se vuelven a abrir los listeners.
    private fun iniciarGlobal() {
        val usuario = Sesion.usuario
        if (usuario == null) {
            globalEstado.cargando = false
            globalEstado.error = "Reconecta tu cuenta de Google desde Perfil para ver Global nativo."
            return
        }
        globalEstado.error = null
        globalEstado.cargando = true
        lifecycleScope.launch {
            try {
                val antes = globalEstado.favoritas
                val datos = GlobalRepositorio.cargarDatosUsuario(usuario.uid)
                val nuevas = datos.ligas
                globalEstado.favoritas = nuevas
                // Introducción de 5 pantallas: solo la primera vez que el usuario entra
                if (!datos.onboardingVisto && !onboardingCerrado) globalEstado.mostrarOnboarding = true
                // Igual que la web: si el usuario acaba de marcar su primera favorita y el
                // interruptor estaba apagado, se enciende solo (nunca se apaga solo).
                if (favoritasConocidas && antes.isEmpty() && nuevas.isNotEmpty() && !globalEstado.soloFavoritos) {
                    globalEstado.soloFavoritos = true
                }
                favoritasConocidas = true
            } catch (e: Exception) {
                // sin favoritas: se muestran todos los partidos igual
            }
            suscribirPartidosGlobal()
            solicitarRecargaPredicciones()
        }
    }

    private fun suscribirPartidosGlobal() {
        globalEstado.cargando = true
        val alFallar: (Exception) -> Unit = { fallo ->
            globalEstado.cargando = false
            globalEstado.grupos = emptyList()
            globalEstado.error = "No se pudieron cargar los partidos: " + (fallo.message ?: fallo.toString())
        }
        val alCambiar: (List<GrupoLiga>) -> Unit = { grupos ->
            globalEstado.grupos = grupos
            globalEstado.cargando = false
            globalEstado.error = null
            asegurarRankings(grupos)
            solicitarRecargaPredicciones()
        }

        if (globalEstado.soloFavoritos) {
            escuchaPartidos.favoritas(globalEstado.fecha, globalEstado.favoritas, alCambiar, alFallar)
        } else {
            escuchaPartidos.todas(globalEstado.fecha, alCambiar, alFallar)
        }
    }

    // Igual que la web: al listar una liga se crea mi fila de ranking en 0 si todavía no existe
    private fun asegurarRankings(grupos: List<GrupoLiga>) {
        val usuario = Sesion.usuario ?: return
        for (grupo in grupos) {
            if (grupo.partidos.isEmpty() || !rankingsAsegurados.add(grupo.competenciaId)) continue
            lifecycleScope.launch {
                try {
                    GlobalRepositorio.asegurarRanking(grupo.competenciaId, usuario.uid)
                } catch (e: Exception) {
                    rankingsAsegurados.remove(grupo.competenciaId) // se reintenta en la próxima actualización
                }
            }
        }
    }

    // Los puntos viven en las predicciones, no en el partido: se recargan cuando llega una
    // actualización (varias seguidas se juntan en una sola lectura).
    private fun solicitarRecargaPredicciones() {
        if (recargaPrediccionesPendiente) return
        val usuario = Sesion.usuario ?: return
        recargaPrediccionesPendiente = true
        lifecycleScope.launch {
            delay(400)
            recargaPrediccionesPendiente = false
            if (globalEstado.modo == "quiniela") {
                try {
                    globalEstado.prediccionesQuiniela = GlobalRepositorio.cargarEleccionesQuiniela(usuario.uid)
                    globalEstado.errorQuiniela = null
                } catch (e: Exception) {
                    globalEstado.errorQuiniela = e.message ?: e.toString()
                }
            } else {
                try {
                    globalEstado.prediccionesExacto = GlobalRepositorio.cargarPrediccionesExacto(usuario.uid)
                } catch (e: Exception) {
                    // se muestran sin predicción hasta la próxima actualización
                }
            }
        }
    }

    private fun terminarOnboarding() {
        globalEstado.mostrarOnboarding = false
        onboardingCerrado = true
        val usuario = Sesion.usuario ?: return
        lifecycleScope.launch {
            try {
                GlobalRepositorio.marcarOnboardingVisto(usuario.uid)
            } catch (e: Exception) {
                // si no se pudo guardar, se vuelve a mostrar la próxima vez
            }
        }
    }

    // Tras guardar, se releen los puntos
    private fun alGuardarPredicciones(
        exacto: Map<String, PrediccionExacto>,
        quiniela: Map<String, EleccionQuiniela>
    ) {
        solicitarRecargaPredicciones()
    }

    private fun globalElegirFecha(iso: String) {
        globalEstado.fecha = iso
        suscribirPartidosGlobal()
    }

    private fun globalAlternarFavoritos(soloFavoritas: Boolean) {
        globalEstado.soloFavoritos = soloFavoritas
        suscribirPartidosGlobal()
    }

    // El modo de juego es compartido por todas las pantallas.
    private fun globalCambiarModo(modo: String) {
        globalEstado.modo = modo
        torneosAlCambiarModo(modo)
        solicitarRecargaPredicciones()
    }

    // Si pasa la medianoche con la app abierta, "Hoy" sigue siendo hoy
    private fun globalRevisarDia() {
        val hoy = LocalDate.now().toString()
        if (hoy == diaGlobal) return
        val seguiaHoy = globalEstado.fecha == diaGlobal
        diaGlobal = hoy
        if (seguiaHoy) {
            globalEstado.fecha = hoy
            if (globalView.visibility == View.VISIBLE) suscribirPartidosGlobal()
        }
    }

    // ---------- Perfil nativo ----------

    // El perfil se ve cuando la pestaña activa es Perfil y no hay una página web encima.
    private fun actualizarPerfil() {
        val debeVerse = navView.visibility == View.VISIBLE &&
            tabActual == "perfil"
        val yaSeVeia = perfilView.visibility == View.VISIBLE

        perfilView.visibility = if (debeVerse) View.VISIBLE else View.GONE
        if (debeVerse && !yaSeVeia) cargarPerfil()

        actualizarGlobal()
        actualizarTorneos()
        actualizarTorneoDetalle()
        actualizarCrearTorneo()
        actualizarMisTorneos()
        actualizarHistorial()
        actualizarRanking()
        actualizarLigasFavoritas()
    }

    private fun cargarPerfil() {
        val usuario = Sesion.usuario
        perfilEstado.sesionNativa = usuario != null
        if (usuario == null) {
            // Sesión de la web abierta, pero la nativa no (por ejemplo, tras actualizar la app)
            perfilEstado.necesitaReconectar = true
            return
        }
        perfilEstado.necesitaReconectar = false
        perfilEstado.mensajeReconexion = ""
        perfilEstado.errorCarga = null
        perfilEstado.miembroDesde = usuario.metadata?.creationTimestamp?.let { mesYAnio(it) }

        val uid = usuario.uid
        lifecycleScope.launch {
            launch {
                try {
                    val datos = PerfilRepositorio.cargarUsuario(uid, usuario.email)
                    perfilEstado.nombre = datos.nombre
                    perfilEstado.correo = datos.correo
                    perfilEstado.fotoBase64 = datos.fotoBase64
                } catch (e: Exception) {
                    perfilEstado.nombre = "Error al cargar"
                    perfilEstado.errorCarga = "Datos del usuario: " + (e.message ?: e.toString())
                }
            }
        }
    }

    private fun mesYAnio(milisegundos: Long): String {
        val meses = listOf(
            "enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"
        )
        val fecha = Calendar.getInstance().apply { timeInMillis = milisegundos }
        return "${meses[fecha.get(Calendar.MONTH)]} ${fecha.get(Calendar.YEAR)}"
    }

    private fun guardarNombre(nuevo: String) {
        val usuario = Sesion.usuario ?: return
        val anterior = perfilEstado.nombre
        perfilEstado.nombre = nuevo // se ve de inmediato; si falla, se recupera el anterior
        lifecycleScope.launch {
            try {
                PerfilRepositorio.actualizarNombre(usuario.uid, nuevo)
            } catch (e: Exception) {
                perfilEstado.nombre = anterior
                Toast.makeText(
                    this@MainActivity,
                    "No se pudo actualizar el nombre. Prueba de nuevo.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Todos los accesos de Perfil son pantallas nativas
    private fun abrirAcceso(accion: String) {
        when (accion) {
            "mis_torneos" -> abrirMisTorneos()
            "historial" -> abrirHistorialGlobal()
            "ranking" -> abrirRankingGlobal()
            "ligas_favoritas" -> abrirLigasFavoritas("perfil")
            "privacidad" -> abrirEnlace(URL_POLITICA_PRIVACIDAD)
            "terminos" -> abrirEnlace(URL_TERMINOS_Y_CONDICIONES)
            "reportar" -> reportarProblema()
        }
    }

    // Abre una página web en el navegador del teléfono
    private fun abrirEnlace(url: String) {
        if (url.isBlank()) {
            Toast.makeText(this, "Este enlace todavía no está disponible.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No se encontró un navegador para abrir el enlace.", Toast.LENGTH_LONG).show()
        }
    }

    // Abre la app de correo con un mensaje listo para escribirnos, con los datos básicos del teléfono
    // para que el soporte sepa en qué versión ocurrió. No incluye datos de la cuenta.
    private fun reportarProblema() {
        if (CORREO_SOPORTE.isBlank()) {
            Toast.makeText(this, "El correo de soporte todavía no está configurado.", Toast.LENGTH_LONG).show()
            return
        }
        val cuerpo = "\n\n---\nDatos para el soporte (no los borres):\n" +
            "Versión de la app: ${BuildConfig.VERSION_NAME}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}\n"
        val correo = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(CORREO_SOPORTE))
            putExtra(Intent.EXTRA_SUBJECT, "Reporte de un problema - Predictorus")
            putExtra(Intent.EXTRA_TEXT, cuerpo)
        }
        try {
            startActivity(correo)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No hay una app de correo instalada.", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Prueba de conexión (Perfil > Pruebas) ----------

    private fun huellaSha1(): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val firma = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
            MessageDigest.getInstance("SHA-1").digest(firma.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    // Cualquier código HTTP significa que la red llegó hasta el servidor
    private suspend fun sondear(direccion: String): String = withContext(Dispatchers.IO) {
        val inicio = System.currentTimeMillis()
        try {
            val conexion = URL(direccion).openConnection() as HttpURLConnection
            conexion.connectTimeout = 8000
            conexion.readTimeout = 8000
            conexion.instanceFollowRedirects = false
            val codigo = conexion.responseCode
            conexion.disconnect()
            "$direccion → HTTP $codigo (${System.currentTimeMillis() - inicio} ms)"
        } catch (e: Exception) {
            "$direccion → ${e.javaClass.simpleName}: ${e.message} (${System.currentTimeMillis() - inicio} ms)"
        }
    }

    // Misma petición que haría el SDK nativo: con la clave de API y la firma de esta instalación
    private suspend fun sondearConClave(): String = withContext(Dispatchers.IO) {
        val inicio = System.currentTimeMillis()
        try {
            val opciones = FirebaseApp.getInstance().options
            val direccion = "https://firestore.googleapis.com/v1/projects/${opciones.projectId}" +
                "/databases/(default)/documents/usuarios/diagnostico?key=${opciones.apiKey}"
            val conexion = URL(direccion).openConnection() as HttpURLConnection
            conexion.connectTimeout = 8000
            conexion.readTimeout = 8000
            conexion.setRequestProperty("X-Android-Package", packageName)
            huellaSha1()?.let { conexion.setRequestProperty("X-Android-Cert", it) }
            val codigo = conexion.responseCode
            val flujo = if (codigo >= 400) conexion.errorStream else conexion.inputStream
            val cuerpo = flujo?.bufferedReader()?.use { it.readText() }
                ?.replace(Regex("\\s+"), " ")?.take(280) ?: ""
            conexion.disconnect()
            "HTTP $codigo (${System.currentTimeMillis() - inicio} ms) $cuerpo"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message} (${System.currentTimeMillis() - inicio} ms)"
        }
    }

    private suspend fun sondearFirestore(uid: String?): String {
        if (uid == null) return "sin sesión nativa: no se puede probar"
        val inicio = System.currentTimeMillis()
        return try {
            val documento = withTimeoutOrNull(12_000) {
                FirebaseFirestore.getInstance().collection("usuarios").document(uid)
                    .get(Source.SERVER).await()
            }
            val ms = System.currentTimeMillis() - inicio
            if (documento == null) {
                "sin respuesta en 12 s"
            } else {
                "OK: el documento de tu usuario ${if (documento.exists()) "existe" else "no existe"} ($ms ms)"
            }
        } catch (e: Exception) {
            val codigo = (e as? FirebaseFirestoreException)?.code
            "${e.javaClass.simpleName}${if (codigo != null) " [$codigo]" else ""}: ${e.message} " +
                "(${System.currentTimeMillis() - inicio} ms)"
        }
    }

    // En redes donde IPv6 no responde, la primera conexión a los servidores de Google puede tardar mucho:
    // el teléfono prueba cada dirección IPv6 antes de llegar a una IPv4. Esa espera se adelanta en
    // segundo plano mientras se ve el login, para que al tocar un botón la conexión ya esté lista.
    private fun precalentarConexiones() {
        lifecycleScope.launch(Dispatchers.IO) {
            val destinos = listOf(
                "https://identitytoolkit.googleapis.com/",
                "https://securetoken.googleapis.com/",
                "https://firestore.googleapis.com/"
            )
            coroutineScope {
                destinos.forEach { destino ->
                    launch {
                        try {
                            val conexion = URL(destino).openConnection() as HttpURLConnection
                            conexion.connectTimeout = 15000
                            conexion.readTimeout = 15000
                            conexion.instanceFollowRedirects = false
                            conexion.responseCode
                            // Se lee la respuesta completa para que la conexión quede disponible
                            (conexion.errorStream ?: conexion.inputStream)?.use { it.readBytes() }
                        } catch (e: Exception) {
                            // Solo es un calentamiento: si falla, no pasa nada
                        }
                    }
                }
                launch {
                    try {
                        FirebaseFirestore.getInstance().collection("equipos").document("_calentar").get().await()
                    } catch (e: Exception) {
                        // Igual que arriba
                    }
                }
            }
        }
    }

    // Resuelve un servidor de Google y prueba por separado cada dirección IP (IPv6 e IPv4) en el
    // puerto 443. Sirve para detectar redes donde una de las dos no responde y la app se queda esperando.
    private suspend fun sondearDirecciones(host: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val inicioDns = System.currentTimeMillis()
            val direcciones = InetAddress.getAllByName(host)
            val msDns = System.currentTimeMillis() - inicioDns
            val resultados = coroutineScope {
                direcciones.map { direccion ->
                    async {
                        val inicio = System.currentTimeMillis()
                        val tipo = if (direccion is Inet6Address) "IPv6" else "IPv4"
                        try {
                            Socket().use { socket ->
                                socket.connect(InetSocketAddress(direccion, 443), 6000)
                            }
                            "  $tipo ${direccion.hostAddress}: conecta en ${System.currentTimeMillis() - inicio} ms"
                        } catch (e: Exception) {
                            "  $tipo ${direccion.hostAddress}: FALLA (${e.javaClass.simpleName}) tras " +
                                "${System.currentTimeMillis() - inicio} ms"
                        }
                    }
                }.awaitAll()
            }
            listOf("  DNS: $msDns ms, ${direcciones.size} direcciones") + resultados
        } catch (e: Exception) {
            listOf("  no se pudo resolver $host (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    private fun probarConexion() {
        if (perfilEstado.probandoConexion) return
        perfilEstado.probandoConexion = true
        perfilEstado.diagnostico = "Probando…"
        lifecycleScope.launch {
            val usuario = Sesion.usuario
            val huella = huellaSha1()?.chunked(2)?.joinToString(":")?.uppercase() ?: "no disponible"
            val destinos = listOf(
                "https://www.google.com/generate_204",
                "https://firestore.googleapis.com/",
                "https://identitytoolkit.googleapis.com/",
                "https://securetoken.googleapis.com/"
            )
            val sondeos = coroutineScope { destinos.map { async { sondear(it) } }.awaitAll() }
            val conClave = sondearConClave()
            val firestore = sondearFirestore(usuario?.uid)

            val lineas = ArrayList<String>()
            lineas.add("Android ${Build.VERSION.SDK_INT} · ${Build.MODEL}")
            lineas.add("Huella SHA-1 de esta instalación: $huella")
            lineas.add(
                "Sesión nativa: " +
                    (usuario?.let { "activa (…${it.uid.takeLast(6)})" } ?: "cerrada")
            )
            lineas.add(
                getSharedPreferences("ajustes", MODE_PRIVATE).getString("diag_login", null)
                    ?: "Último login: sin datos (cierra sesión y vuelve a entrar para medirlo)"
            )
            lineas.add("")
            lineas.add("Red (cualquier HTTP = llegó al servidor):")
            lineas.addAll(sondeos)
            lineas.add("")
            lineas.add("Petición con la clave de API de la app:")
            lineas.add(conClave)
            lineas.add("")
            lineas.add("Lectura real de Firestore:")
            lineas.add(firestore)

            val (ipFirestore, ipAuth) = coroutineScope {
                val a = async { sondearDirecciones("firestore.googleapis.com") }
                val b = async { sondearDirecciones("identitytoolkit.googleapis.com") }
                Pair(a.await(), b.await())
            }
            lineas.add("")
            lineas.add("Conexión por dirección IP (firestore.googleapis.com):")
            lineas.addAll(ipFirestore)
            lineas.add("")
            lineas.add("Conexión por dirección IP (identitytoolkit.googleapis.com):")
            lineas.addAll(ipAuth)

            lineas.add("")
            lineas.add("Mis predicciones guardadas (Global):")
            val uidActual = usuario?.uid
            if (uidActual != null) {
                lineas.addAll(
                    try {
                        PerfilRepositorio.revisarPredicciones(uidActual)
                    } catch (e: Exception) {
                        listOf("  no se pudo revisar: ${e.message ?: e.javaClass.simpleName}")
                    }
                )
            }

            // Si mientras tanto se cerró la sesión, el resultado ya no corresponde a quien está en la app
            if (Sesion.usuario?.uid != usuario?.uid) {
                perfilEstado.probandoConexion = false
                return@launch
            }
            perfilEstado.diagnostico = lineas.joinToString("\n")
            perfilEstado.probandoConexion = false
        }
    }

    private fun copiarDiagnostico() {
        val portapapeles = getSystemService(ClipboardManager::class.java)
        portapapeles.setPrimaryClip(ClipData.newPlainText("Diagnóstico de conexión", perfilEstado.diagnostico))
        Toast.makeText(this, "Resultado copiado", Toast.LENGTH_SHORT).show()
    }

    private fun elegirFoto() {
        if (Sesion.usuario == null) {
            Toast.makeText(this, "Primero reconecta tu cuenta de Google.", Toast.LENGTH_LONG).show()
            return
        }
        selectorFoto.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun cambiarFoto(uri: Uri) {
        val usuario = Sesion.usuario ?: return
        val anterior = perfilEstado.fotoBase64
        lifecycleScope.launch {
            try {
                val dataUrl = withContext(Dispatchers.Default) {
                    ImagenAvatar.comprimir(this@MainActivity, uri)
                }
                perfilEstado.fotoBase64 = dataUrl // se ve de inmediato
                PerfilRepositorio.actualizarFoto(usuario.uid, dataUrl)
            } catch (e: Exception) {
                perfilEstado.fotoBase64 = anterior
                Toast.makeText(
                    this@MainActivity,
                    "No se pudo actualizar la foto. Prueba de nuevo.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Eliminar cuenta: primero se confirma la identidad (Firebase lo exige), después se borran todos
    // los datos del usuario y por último la cuenta.
    private fun eliminarCuenta() {
        val usuario = Sesion.usuario ?: return
        perfilEstado.eliminando = true
        lifecycleScope.launch {
            try {
                if (usuario.providerData.any { it.providerId == "google.com" }) {
                    // Se confirma con Google (así no hace falta cerrar sesión y volver a entrar).
                    val token = obtenerIdTokenGoogle()
                    usuario.reauthenticate(GoogleAuthProvider.getCredential(token, null)).await()
                } else if (!accesoReciente(usuario)) {
                    // Entró solo con enlace de correo: se confirma con un enlace nuevo
                    pedirConfirmacionPorCorreo(usuario)
                    perfilEstado.eliminando = false
                    return@launch
                }

                PerfilRepositorio.eliminarDatosDelUsuario(usuario.uid)
                try {
                    usuario.delete().await()
                } catch (e: FirebaseAuthRecentLoginRequiredException) {
                    // Los datos ya se borraron; falta confirmar la identidad para cerrar la cuenta
                    pedirConfirmacionPorCorreo(usuario)
                    perfilEstado.eliminando = false
                    return@launch
                }

                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: ClearCredentialException) {
                    // no impide terminar
                }
                perfilEstado.eliminando = false
                mostrarLogin()
            } catch (e: GetCredentialCancellationException) {
                perfilEstado.eliminando = false
            } catch (e: Exception) {
                perfilEstado.eliminando = false
                Toast.makeText(
                    this@MainActivity,
                    "No se pudo eliminar la cuenta: " + (e.message ?: e.toString()),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ¿Confirmó su identidad hace pocos minutos? Firebase lo exige para eliminar la cuenta.
    private suspend fun accesoReciente(usuario: FirebaseUser): Boolean {
        val ultimoAcceso = usuario.getIdToken(true).await().authTimestamp
        return System.currentTimeMillis() / 1000 - ultimoAcceso < 240
    }

    // Envía al correo un enlace para volver a confirmar la identidad
    private suspend fun pedirConfirmacionPorCorreo(usuario: FirebaseUser) {
        val correo = usuario.email
        if (correo.isNullOrEmpty()) {
            throw IllegalStateException("Tu cuenta no tiene un correo para confirmar tu identidad.")
        }
        Sesion.enviarEnlace(correo)
        confirmandoIdentidad = true
        Toast.makeText(
            this,
            "Te enviamos un enlace a $correo para confirmar tu identidad. Ábrelo en este teléfono y vuelve a tocar Eliminar.",
            Toast.LENGTH_LONG
        ).show()
    }

    // El usuario abrió el enlace de confirmación: ya puede volver a pedir la eliminación
    private fun reautenticarConEnlace(enlace: String) {
        val correo = Sesion.usuario?.email ?: return
        confirmandoIdentidad = false
        lifecycleScope.launch {
            try {
                Sesion.reautenticarConEnlace(correo, enlace)
                Toast.makeText(
                    this@MainActivity,
                    "Identidad confirmada. Vuelve a tocar «Eliminar mi cuenta» para terminar.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "No se pudo confirmar tu identidad: " + (e.message ?: e.toString()),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun cerrarSesion() {
        Sesion.cerrar()
        lifecycleScope.launch {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: ClearCredentialException) {
                // Si falla, igual se cierran las sesiones de Firebase.
            }
            mostrarLogin()
        }
    }

    // Abre de nuevo la sesión cuando se perdió (la pide el Perfil).
    private fun reconectarNativo() {
        lifecycleScope.launch {
            try {
                val token = obtenerIdTokenGoogle()
                Sesion.entrarConGoogle(token)
                cargarPerfil()
            } catch (e: GetCredentialCancellationException) {
                // El usuario cerró el selector de cuentas
            } catch (e: Exception) {
                perfilEstado.mensajeReconexion = "No se pudo reconectar: " + (e.message ?: e.toString())
            }
        }
    }

    // ---------- Pantallas nativas (login y datos) ----------

    private fun mostrarLogin() {
        loginEstado.cargando = false
        loginEstado.esError = false
        loginEstado.mensaje = ""
        loginEstado.enlacePendiente = null
        datosView.visibility = View.GONE
        loginView.visibility = View.VISIBLE
        Sesion.cerrar()
        // Todo lo de la sesión anterior se olvida: lo que esa persona eligió (día, filtros, modo,
        // resultado de la prueba de conexión) no debe aparecer al entrar con otra cuenta
        perfilEstado.reiniciar()
        globalEstado.reiniciarParaNuevaSesion()
        torneosEstado.limpiar()
        torneosControlador.marcarObsoleto()
        torneoDetalleControlador.cerrar()
        torneosEstado.modo = "exacto"
        misTorneosEstado.modo = "exacto"
        historialEstado.modo = "exacto"
        rankingEstado.modo = "exacto"
        ligasFavoritasEstado.modo = "exacto"
        misTorneosControlador.cerrar()
        historialControlador.cerrar()
        rankingControlador.cerrar()
        ligasFavoritasControlador.cerrar()
        burbujaControlador.cerrar()
        crearTorneoControlador.cerrar()
        favoritasConocidas = false
        onboardingCerrado = false
        rankingsAsegurados.clear()
        ocultarNav()
        ocultarSplash()
        precalentarConexiones()
    }

    private fun mostrarDatos() {
        loginEstado.cargando = false
        datosEstado.cargando = false
        datosEstado.esError = false
        datosEstado.mensaje = ""
        // Cada vez que se muestra la pantalla de datos, hay que aceptar los documentos de nuevo
        datosEstado.aceptaPrivacidad = false
        datosEstado.aceptaTerminos = false
        loginView.visibility = View.GONE
        datosView.visibility = View.VISIBLE
        ocultarNav()
        ocultarSplash()
    }

    // Entra a la app: oculta login y datos y muestra la barra inferior con la pestaña Global
    private fun entrarALaApp() {
        loginEstado.cargando = false
        loginEstado.mensaje = ""
        datosEstado.cargando = false
        loginView.visibility = View.GONE
        datosView.visibility = View.GONE
        tabActual = "global"
        mostrarNav()
        ocultarSplash()
        Sesion.usuario?.uid?.let { repararPrediccionesAntiguas(it) }
    }

    // Una sola vez por cuenta: completa las predicciones viejas que se guardaron sin fecha, para que
    // el Historial muestre todas las que cuenta el Perfil. Se hace en segundo plano.
    private fun repararPrediccionesAntiguas(uid: String) {
        val prefs = getSharedPreferences("ajustes", MODE_PRIVATE)
        val clave = "predicciones_reparadas_v1_$uid"
        if (prefs.getBoolean(clave, false)) return
        lifecycleScope.launch {
            try {
                PerfilRepositorio.revisarPredicciones(uid)
                prefs.edit().putBoolean(clave, true).apply()
            } catch (e: Exception) {
                // Se reintenta la próxima vez que se abra la app
            }
        }
    }

    // ---------- Barra inferior ----------

    private fun mostrarNav() {
        navView.visibility = View.VISIBLE
        actualizarPerfil()
        // La lista de Torneos se precarga para que ya esté lista al abrir la pestaña
        if (Sesion.usuario != null) torneosControlador.reiniciarSiHaceFalta()
    }

    private fun ocultarNav() {
        navView.visibility = View.GONE
        actualizarPerfil()
    }

    private fun elegirTab(tab: String) {
        // Tocar cualquier pestaña cierra Mis torneos (tocar Perfil lleva de vuelta al Perfil)
        if (misTorneosEstado.abierto) misTorneosControlador.cerrar()
        if (historialEstado.abierto) historialControlador.cerrar()
        if (rankingEstado.abierto) rankingControlador.cerrar()
        if (ligasFavoritasEstado.abierto) ligasFavoritasControlador.cerrar()
        volverAMisTorneos = false
        tabActual = tab
        actualizarPerfil()
    }

    private fun entrarConGoogle() {
        loginEstado.cargando = true
        loginEstado.esError = false
        loginEstado.mensaje = "Conectando con Google…"
        lifecycleScope.launch {
            var sesionAbierta = false
            try {
                val inicioSelector = System.currentTimeMillis()
                val token = obtenerIdTokenGoogle()
                val tiempoSelector = System.currentTimeMillis() - inicioSelector
                // El tiempo y el motivo de un fallo se guardan para la "Prueba de conexión" de Perfil.
                abrirSesionMedida(token, tiempoSelector)
                sesionAbierta = true
                loginEstado.mensaje = "Cargando tu perfil…"
                irSegunPerfil()
            } catch (e: GetCredentialCancellationException) {
                // El usuario cerró el selector de cuentas: no es un error.
                loginEstado.cargando = false
                loginEstado.mensaje = ""
            } catch (e: FirebaseAuthUserCollisionException) {
                loginEstado.cargando = false
                loginEstado.esError = true
                loginEstado.mensaje = "Ya tienes una cuenta con este correo registrada por email. Solicita el link de acceso en su lugar."
            } catch (e: Exception) {
                loginEstado.cargando = false
                loginEstado.esError = true
                val detalle = e.message ?: e.toString()
                loginEstado.mensaje = if (sesionAbierta) {
                    // Google ya aceptó el acceso: lo que falló fue cargar el perfil
                    "Entraste con Google, pero no se pudo cargar tu perfil. Revisa tu conexión e inténtalo de nuevo. ($detalle)"
                } else {
                    "No se pudo conectar con Google: $detalle"
                }
            }
        }
    }

    private suspend fun abrirSesionMedida(token: String, tiempoSelectorMs: Long) {
        val inicio = System.currentTimeMillis()
        try {
            Sesion.entrarConGoogle(token)
            guardarDiagnosticoLogin(
                "Último login: selector de Google $tiempoSelectorMs ms · " +
                    "Firebase ${System.currentTimeMillis() - inicio} ms (OK)"
            )
        } catch (e: Exception) {
            guardarDiagnosticoLogin(
                "Último login: selector de Google $tiempoSelectorMs ms · " +
                    "Firebase ${System.currentTimeMillis() - inicio} ms " +
                    "(FALLÓ → ${e.javaClass.simpleName}: ${e.message})"
            )
            throw e
        }
    }

    private fun guardarDiagnosticoLogin(texto: String) {
        getSharedPreferences("ajustes", MODE_PRIVATE).edit().putString("diag_login", texto).apply()
    }

    private fun pedirLinkPorCorreo(email: String) {
        val correo = email.trim()
        loginEstado.cargando = true
        loginEstado.esError = false
        loginEstado.mensaje = "Enviando link de acceso…"
        lifecycleScope.launch {
            // Si la conexión con los servidores de Google está lenta, se avisa para que no parezca que se colgó
            val avisoLento = launch {
                delay(8000)
                if (loginEstado.cargando) {
                    loginEstado.mensaje = "Sigue enviando… tu conexión con los servidores de Google está lenta."
                }
            }
            try {
                Sesion.enviarEnlace(correo)
                // Se recuerda el correo para completar el acceso al abrir el enlace en este teléfono
                getSharedPreferences("ajustes", MODE_PRIVATE).edit()
                    .putString("correo_pendiente", correo).apply()
                loginEstado.esError = false
                loginEstado.mensaje = "Listo, te mandamos un link a $correo. Ábrelo desde este mismo dispositivo."
            } catch (e: Exception) {
                loginEstado.esError = true
                loginEstado.mensaje = "Error: " + (e.message ?: e.toString())
            } finally {
                avisoLento.cancel()
                loginEstado.cargando = false
            }
        }
    }

    // El enlace llegó a un teléfono sin el correo guardado: el usuario lo confirma en el diálogo
    private fun confirmarCorreoEnlace(correo: String) {
        val enlace = loginEstado.enlacePendiente ?: return
        loginEstado.enlacePendiente = null
        completarEnlace(correo, enlace)
    }

    // Se abrió la app desde el enlace del correo: se completa el acceso
    private fun procesarEnlace(intento: Intent?) {
        if (intento == null) return
        val enlace = intento.data?.toString() ?: return
        intento.data = null
        if (!Sesion.esEnlaceDeCorreo(enlace)) return
        if (Sesion.usuario != null) {
            // Con la sesión abierta, el enlace solo sirve para confirmar la identidad al eliminar la cuenta
            if (confirmandoIdentidad) reautenticarConEnlace(enlace)
            return
        }
        val correo = getSharedPreferences("ajustes", MODE_PRIVATE).getString("correo_pendiente", null)
        if (correo.isNullOrEmpty()) {
            loginEstado.enlacePendiente = enlace
        } else {
            completarEnlace(correo, enlace)
        }
    }

    private fun completarEnlace(correo: String, enlace: String) {
        loginEstado.cargando = true
        loginEstado.esError = false
        loginEstado.mensaje = "Verificando…"
        lifecycleScope.launch {
            try {
                Sesion.entrarConEnlace(correo, enlace)
                getSharedPreferences("ajustes", MODE_PRIVATE).edit().remove("correo_pendiente").apply()
                irSegunPerfil()
            } catch (e: Exception) {
                loginEstado.cargando = false
                loginEstado.esError = true
                loginEstado.mensaje = "No se pudo verificar el link: " + (e.message ?: e.toString())
            }
        }
    }

    private fun arrancar() {
        val usuario = Sesion.usuario
        if (usuario == null) {
            mostrarLogin()
            return
        }
        lifecycleScope.launch {
            try {
                irSegunPerfil()
            } catch (e: Exception) {
                // Sin conexión: se entra con la sesión guardada, salvo que ya se sepa que esta
                // persona todavía no terminó su registro (pantalla de datos)
                if (registroPendiente(usuario.uid)) mostrarDatos() else entrarALaApp()
            }
        }
    }

    private suspend fun irSegunPerfil() {
        val usuario = Sesion.usuario ?: return
        if (PerfilRepositorio.existeUsuario(usuario.uid)) {
            marcarRegistroPendiente(usuario.uid, false)
            entrarALaApp()
        } else {
            marcarRegistroPendiente(usuario.uid, true)
            mostrarDatos()
        }
    }

    // Recuerda en el teléfono si el usuario todavía no terminó su registro
    private fun marcarRegistroPendiente(uid: String, pendiente: Boolean) {
        getSharedPreferences("ajustes", MODE_PRIVATE).edit()
            .putBoolean("registro_pendiente_$uid", pendiente).apply()
    }

    private fun registroPendiente(uid: String): Boolean =
        getSharedPreferences("ajustes", MODE_PRIVATE).getBoolean("registro_pendiente_$uid", false)

    private fun guardarDatos(nombre: String) {
        if (!datosEstado.aceptoTodo) {
            datosEstado.esError = true
            datosEstado.mensaje = "Acepta la Política de Privacidad y los Términos y Condiciones."
            return
        }
        val usuario = Sesion.usuario
        if (usuario == null) {
            datosEstado.esError = true
            datosEstado.mensaje = "No se encontró tu sesión. Cierra la app y vuelve a entrar."
            return
        }
        datosEstado.cargando = true
        datosEstado.esError = false
        datosEstado.mensaje = "Guardando…"
        lifecycleScope.launch {
            try {
                PerfilRepositorio.crearUsuario(usuario.uid, nombre, VERSION_DOCUMENTOS)
                marcarRegistroPendiente(usuario.uid, false)
                entrarALaApp()
            } catch (e: Exception) {
                datosEstado.cargando = false
                datosEstado.esError = true
                datosEstado.mensaje = "No se pudo guardar: " + (e.message ?: e.toString())
            }
        }
    }

    // ---------- Google (Credential Manager) ----------

    private suspend fun obtenerIdTokenGoogle(): String {
        // Google puede devolver el mismo token mientras lo tenga guardado, aunque ya lleve cerca de una
        // hora y Firebase lo rechace por viejo. Se borra el estado guardado para que emita uno nuevo.
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            // no impide pedir uno nuevo
        }
        val opcion = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_SERVER_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .build()
        val pedido = GetCredentialRequest.Builder()
            .addCredentialOption(opcion)
            .build()

        val credencial = credentialManager.getCredential(this, pedido).credential

        if (credencial is CustomCredential &&
            credencial.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            return GoogleIdTokenCredential.createFrom(credencial.data).idToken
        }
        throw IllegalStateException("Tipo de credencial no reconocido")
    }

    // ---------- Splash ----------

    fun ocultarSplash() {
        splashVisible = false
    }

    // ---------- Ciclo de vida ----------

    override fun onResume() {
        super.onResume()
        if (primerResume) {
            primerResume = false
        } else {
            globalRevisarDia()
        }
    }

    override fun onDestroy() {
        escuchaPartidos.detener()
        super.onDestroy()
    }
}
