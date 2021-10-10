// Modified from https://github.com/ladrahul25/CoroutineAsyncTask/blob/master/app/src/main/java/com/example/background/CoroutinesAsyncTask.kt
package uk.co.cgfindies.youtubevogthumbnailcreator

import android.util.Log
import kotlinx.coroutines.*

enum class Status {
    PENDING,
    RUNNING,
    FINISHED
}

@DelicateCoroutinesApi
abstract class CoroutinesAsyncTask<Params, Progress, Result>(private val taskName: String) {
    private val tag: String by lazy {
        CoroutinesAsyncTask::class.java.simpleName
    }

    private var status: Status = Status.PENDING
    private var preJob: Job? = null
    private var bgJob: Deferred<Result>? = null
    abstract fun doInBackground(vararg params: Params?): Result
    open fun onProgressUpdate(vararg values: Progress?) {}
    open fun onPostExecute(result: Result?) {}
    open fun onPreExecute() {}
    open fun onCancelled(result: Result?) {}
    private var isCancelled = false

    /**
     * Executes background task parallel with other background tasks in the queue using
     * default thread pool
     */
    fun execute(vararg params: Params?) {

        if (status != Status.PENDING) {
            when (status) {
                Status.RUNNING -> throw IllegalStateException("Cannot execute task:" + " the task is already running.")
                Status.FINISHED -> throw IllegalStateException("Cannot execute task:"
                        + " the task has already been executed "
                        + "(a task can be executed only once)")
                else -> {
                }
            }
        }

        status = Status.RUNNING

        // it can be used to setup UI - it should have access to Main Thread
        GlobalScope.launch(Dispatchers.Main) {
            preJob = launch(Dispatchers.Main) {
                printLog("$taskName onPreExecute started")
                onPreExecute()
                printLog("$taskName onPreExecute finished")
                bgJob = async(Dispatchers.Default) {
                    printLog("$taskName doInBackground started")
                    doInBackground(*params)
                }
            }
            preJob!!.join()
            if (!isCancelled) {
                withContext(Dispatchers.Main) {
                    onPostExecute(bgJob!!.await())
                    printLog("$taskName doInBackground finished")
                    status = Status.FINISHED
                }
            }
        }
    }

    fun cancel(mayInterruptIfRunning: Boolean) {
        if (preJob == null || bgJob == null) {
            printLog("$taskName has already been cancelled/finished/not yet started.")
            return
        }
        if (mayInterruptIfRunning || (!preJob!!.isActive && !bgJob!!.isActive)) {
            isCancelled = true
            status = Status.FINISHED
            printLog("Running onCancelled")
            GlobalScope.launch(Dispatchers.Main) {
                onCancelled(null)
            }

            printLog("Cancelling preJob")
            preJob?.cancel(CancellationException("PreExecute: Coroutine Task cancelled"))
            printLog("Cancelling bgJob")
            bgJob?.cancel(CancellationException("doInBackground: Coroutine Task cancelled"))
            printLog("$taskName has been cancelled.")
        }
    }

    // publishProgress is part of the public contract that we may use later on
    @Suppress("unused")
    fun publishProgress(vararg progress: Progress) {
        //need to update main thread
        GlobalScope.launch(Dispatchers.Main) {
            if (!isCancelled) {
                onProgressUpdate(*progress)
            }
        }
    }

    private fun printLog(message: String) {
        Log.d(tag, message)
    }
}