package org.robolectric.shadows;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build.VERSION_CODES;
import android.service.notification.NotificationListenerService.Ranking;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Test for {@link ShadowRanking}. */
@RunWith(RobolectricTestRunner.class)
@Config(minSdk = VERSION_CODES.KITKAT_WATCH)
public class ShadowRankingTest {
  private Ranking ranking;

  @Before
  public void setUp() {
    ranking = new Ranking();
  }

  @Test
  @Config(minSdk = VERSION_CODES.O)
  public void setChannel() {
    NotificationChannel notificationChannel =
        new NotificationChannel("test_id", "test_name", NotificationManager.IMPORTANCE_DEFAULT);

    shadowOf(ranking).setChannel(notificationChannel);

    assertThat(ranking.getChannel()).isEqualTo(notificationChannel);
  }

  @Test
  @Config(minSdk = VERSION_CODES.P)
  public void testSetHiddenTrue() {
    boolean hidden = true;

    shadowOf(ranking).setHidden(hidden);

    assertThat(ranking.isSuspended()).isEqualTo(hidden);
  }

  @Test
  @Config(minSdk = VERSION_CODES.P)
  public void testSetHiddenFalse() {
    boolean hidden = false;

    shadowOf(ranking).setHidden(hidden);

    assertThat(ranking.isSuspended()).isEqualTo(hidden);
  }
}
