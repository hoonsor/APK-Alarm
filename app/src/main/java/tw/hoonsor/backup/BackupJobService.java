package tw.hoonsor.backup;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** JobScheduler 每天叫醒一次，在背景執行緒跑 {@link AutoBackup#runOnce}。失敗不重試，隔天再跑 */
public class BackupJobService extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!AutoBackup.isEnabled(this)) return false;
        new Thread(() -> {
            AutoBackup.runOnce(getApplicationContext(), false);
            jobFinished(params, false);
        }, "auto-backup-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
