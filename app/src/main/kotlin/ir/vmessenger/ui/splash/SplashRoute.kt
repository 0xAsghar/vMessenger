package ir.vmessenger.ui.splash

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ir.vmessenger.R
import ir.vmessenger.core.designsystem.R as DesignR

@Composable
fun SplashRoute() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(DesignR.drawable.ic_vmessenger_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(96.dp),
        )
    }
}
