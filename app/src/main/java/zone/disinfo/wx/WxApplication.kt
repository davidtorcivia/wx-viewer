package zone.disinfo.wx

import android.app.Application
import zone.disinfo.wx.alerts.AlertScheduler

class WxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AlertScheduler.sync(this)
    }
}
