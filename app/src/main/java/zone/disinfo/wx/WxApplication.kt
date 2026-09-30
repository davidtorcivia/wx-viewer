package zone.disinfo.wx

import android.app.Application
import zone.disinfo.wx.alerts.AlertScheduler
import zone.disinfo.wx.data.DisplayCache
import zone.disinfo.wx.data.WeatherRepository

class WxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DisplayCache.initialize(this)
        WeatherRepository.startCacheMigration(this)
        AlertScheduler.sync(this)
    }
}
