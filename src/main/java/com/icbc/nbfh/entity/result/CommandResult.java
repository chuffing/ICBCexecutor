package com.icbc.nbfh.entity.result;

import lombok.Getter;

/**
 * 固定脚本进程的执行结果。
 */
@Getter
public class CommandResult {

    /** 进程退出码；超时时为-1。 */
    private final int exitCode;

    /** 进程标准输出。 */
    private final String stdout;

    /** 进程错误输出。 */
    private final String stderr;

    /** 进程是否因超过规定时间而被终止。 */
    private final boolean timedOut;

    /** 进程开始时间，Unix毫秒时间戳。 */
    private final long startTime;

    /** 进程结束时间，Unix毫秒时间戳。 */
    private final long endTime;

    private CommandResult(int exitCode, String stdout, String stderr,
                          boolean timedOut, long startTime, long endTime) {
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.timedOut = timedOut;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /** 创建正常结束的进程结果。 */
    public static CommandResult completed(int exitCode, String stdout, String stderr,
                                          long startTime, long endTime) {
        return new CommandResult(exitCode, stdout, stderr, false, startTime, endTime);
    }

    /** 创建执行超时的进程结果。 */
    public static CommandResult timedOut(String stdout, String stderr,
                                         long startTime, long endTime) {
        return new CommandResult(-1, stdout, stderr, true, startTime, endTime);
    }
}
