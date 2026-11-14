package com.payflow.android

import android.os.Bundle
import android.util.Log
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.payflow.android.presentation.checkout.CheckoutEffect
import com.payflow.android.presentation.checkout.CheckoutViewModel
import com.payflow.android.presentation.history.HistoryViewModel
import com.payflow.shared.PaymentAttempt
import com.payflow.shared.PaymentStatus
import com.stripe.android.core.exception.StripeException
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import dagger.hilt.android.AndroidEntryPoint
import java.math.BigDecimal
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val checkoutViewModel: CheckoutViewModel by viewModels()
    private val historyViewModel: HistoryViewModel by viewModels()
    private lateinit var paymentSheet: PaymentSheet

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isDarkMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isDarkMode

        paymentSheet = PaymentSheet.Builder(::onPaymentSheetResult).build(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                checkoutViewModel.effects.collect { effect ->
                    when (effect) {
                        is CheckoutEffect.PresentStripePaymentSheet -> paymentSheet.presentWithPaymentIntent(
                            effect.clientSecret,
                            PaymentSheet.Configuration.Builder("PayFlow").build(),
                        )
                    }
                }
            }
        }

        setContent {
            PayFlowTheme {
                PayFlowApp(
                    checkoutViewModel = checkoutViewModel,
                    historyViewModel = historyViewModel,
                    stripeConfigured = BuildConfig.STRIPE_PUBLISHABLE_KEY.startsWith("pk_"),
                )
            }
        }
        checkoutViewModel.restoreLatestPaymentForOrder()
    }

    private fun onPaymentSheetResult(result: PaymentSheetResult) {
        when (result) {
            is PaymentSheetResult.Completed -> {
                logPaymentSheetResult("completed")
                checkoutViewModel.onStripeSheetClosed()
            }
            is PaymentSheetResult.Canceled -> {
                logPaymentSheetResult("canceled")
                checkoutViewModel.onStripeSheetClosed()
            }
            is PaymentSheetResult.Failed -> {
                logPaymentSheetFailure(result.error)
                checkoutViewModel.onStripeSheetFailed()
            }
        }
    }

    private fun logPaymentSheetResult(result: String) {
        if (BuildConfig.DEBUG) Log.i(PAYMENT_SHEET_LOG_TAG, "result=$result")
    }

    private fun logPaymentSheetFailure(error: Throwable) {
        if (BuildConfig.DEBUG) {
            val stripeError = error as? StripeException
            Log.i(
                PAYMENT_SHEET_LOG_TAG,
                "result=failed errorType=${error.javaClass.simpleName} " +
                    "causeType=${error.cause?.javaClass?.simpleName ?: "none"} " +
                    "statusCode=${stripeError?.statusCode ?: 0} " +
                    "stripeErrorType=${stripeError?.stripeError?.type ?: "none"} " +
                    "stripeErrorCode=${stripeError?.stripeError?.code ?: "none"}",
            )
        }
    }
}

private enum class AppDestination(val route: String, val label: String) {
    Checkout("checkout", "Checkout"),
    Activity("activity", "Activity"),
}

@Composable
private fun PayFlowApp(
    checkoutViewModel: CheckoutViewModel,
    historyViewModel: HistoryViewModel,
    stripeConfigured: Boolean,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            val selected = currentRoute == destination.route
                            val image = when (destination) {
                                AppDestination.Checkout -> if (selected) Icons.Filled.AccountBalanceWallet else Icons.Outlined.AccountBalanceWallet
                                AppDestination.Activity -> if (selected) Icons.AutoMirrored.Filled.ReceiptLong else Icons.AutoMirrored.Outlined.ReceiptLong
                            }
                            Icon(image, contentDescription = destination.label)
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Checkout.route,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable(AppDestination.Checkout.route) {
                CheckoutScreen(checkoutViewModel, stripeConfigured)
            }
            composable(AppDestination.Activity.route) {
                LaunchedEffect(Unit) { historyViewModel.load() }
                HistoryScreen(historyViewModel)
            }
        }
    }
}

@Composable
private fun CheckoutScreen(viewModel: CheckoutViewModel, stripeConfigured: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val attempt = state.attempt
    val status = attempt?.status
    val canPay = status == null || status in setOf(
        PaymentStatus.FAILED,
        PaymentStatus.CANCELLED,
        PaymentStatus.REQUIRES_ACTION,
        PaymentStatus.REQUIRES_PAYMENT_METHOD,
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column {
                Text("PayFlow", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("A secure, simple way to pay", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("DEMO ORDER", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = formatAmount(
                            attempt?.amountMinor ?: state.order.amountMinor,
                            attempt?.currency ?: state.order.currency,
                        ),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text("Order #${state.order.id}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Payment status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusDot(status)
                        Text(status?.displayName() ?: "Ready to checkout", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        status?.statusDescription() ?: "Your payment details are handled securely by Stripe. PayFlow never stores card numbers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    attempt?.let {
                        Text("Provider · ${it.provider.replaceFirstChar(Char::uppercase)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state.message != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(state.message.orEmpty(), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (canPay) viewModel.startPayment() else viewModel.checkPaymentStatus()
                    },
                    enabled = stripeConfigured && !state.isLoading && status != PaymentStatus.SUCCEEDED,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            when {
                                status == PaymentStatus.SUCCEEDED -> "Payment complete"
                                status == PaymentStatus.FAILED || status == PaymentStatus.CANCELLED -> "Try payment again"
                                status == PaymentStatus.REQUIRES_ACTION || status == PaymentStatus.REQUIRES_PAYMENT_METHOD -> "Continue checkout"
                                status == null -> "Continue to payment"
                                else -> "Refresh payment status"
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
                if (status == PaymentStatus.SUCCEEDED) {
                    OutlinedButton(
                        onClick = { viewModel.createNewDemoOrder() },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Create a new demo order")
                    }
                }
                if (status == PaymentStatus.TIMED_OUT || status == PaymentStatus.UNKNOWN) {
                    TextButton(
                        onClick = { viewModel.checkPaymentStatus() },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Check status before trying again") }
                }
                if (!stripeConfigured) {
                    Text(
                        "Set a matching Stripe test publishable key in your local Gradle properties to enable checkout.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item {
            Text(
                "Sandbox mode  ·  Card details handled by Stripe",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HistoryScreen(viewModel: HistoryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Activity", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Your recent payment attempts", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { viewModel.load(force = true) }, enabled = !state.isLoading) { Text("Refresh") }
        }
        Spacer(Modifier.height(18.dp))
        when {
            state.isLoading && state.transactions.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.transactions.isEmpty() -> EmptyHistory(state.message)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.message != null) item { Text(state.message.orEmpty(), color = MaterialTheme.colorScheme.error) }
                items(state.transactions, key = PaymentAttempt::id) { transaction -> TransactionCard(transaction) }
            }
        }
    }
}

@Composable
private fun EmptyHistory(message: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ReceiptLong,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(if (message == null) "No payment activity yet" else "Activity unavailable", style = MaterialTheme.typography.titleMedium)
            Text(message ?: "Your payment attempts will appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TransactionCard(attempt: PaymentAttempt) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusDot(attempt.status)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(attempt.orderId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(attempt.createdAtEpochMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(attempt.status.displayName(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatAmount(attempt.amountMinor, attempt.currency), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatusDot(status: PaymentStatus?) {
    val color = when (status) {
        PaymentStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
        PaymentStatus.FAILED, PaymentStatus.CANCELLED -> MaterialTheme.colorScheme.error
        PaymentStatus.PROCESSING, PaymentStatus.INITIATING, PaymentStatus.TIMED_OUT -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(modifier = Modifier.size(12.dp), shape = CircleShape, color = color, content = {})
}

@Composable
private fun PayFlowTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun PaymentStatus.displayName(): String = when (this) {
    PaymentStatus.CREATED -> "Created"
    PaymentStatus.INITIATING -> "Starting payment"
    PaymentStatus.REQUIRES_PAYMENT_METHOD -> "Payment method needed"
    PaymentStatus.REQUIRES_ACTION -> "Authentication required"
    PaymentStatus.PROCESSING -> "Processing"
    PaymentStatus.SUCCEEDED -> "Paid"
    PaymentStatus.FAILED -> "Failed"
    PaymentStatus.CANCELLED -> "Canceled"
    PaymentStatus.TIMED_OUT -> "Taking longer than expected"
    PaymentStatus.UNKNOWN -> "Status unavailable"
}

private fun PaymentStatus.statusDescription(): String = when (this) {
    PaymentStatus.CREATED, PaymentStatus.INITIATING -> "Your payment is being prepared securely."
    PaymentStatus.REQUIRES_PAYMENT_METHOD -> "Continue to choose a payment method and complete checkout."
    PaymentStatus.REQUIRES_ACTION -> "Complete the secure authentication step to continue."
    PaymentStatus.PROCESSING -> "The payment provider is processing this payment. Check again before retrying."
    PaymentStatus.SUCCEEDED -> "Your payment was confirmed by the payment provider."
    PaymentStatus.FAILED -> "This attempt did not complete. You can safely start another attempt."
    PaymentStatus.CANCELLED -> "This attempt was canceled. You can start a new one when ready."
    PaymentStatus.TIMED_OUT -> "We have not received a final update yet. Check status before retrying."
    PaymentStatus.UNKNOWN -> "We are confirming the latest payment status."
}

private fun formatAmount(amountMinor: Long, currencyCode: String): String = runCatching {
    val currency = Currency.getInstance(currencyCode)
    val fractionDigits = currency.defaultFractionDigits.coerceAtLeast(0)
    NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
        this.currency = currency
        maximumFractionDigits = fractionDigits
        minimumFractionDigits = fractionDigits
    }.format(BigDecimal.valueOf(amountMinor, fractionDigits))
}.getOrDefault("Amount unavailable")

private const val PAYMENT_SHEET_LOG_TAG = "PayFlowPaymentSheet"
