package com.icbc.nbfh.service.impl;

import com.alibaba.fastjson.JSON;
import com.icbc.nbfh.config.AgentProperties;
import com.icbc.nbfh.entity.TaskResultRecord;
import com.icbc.nbfh.service.TaskResultStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 使用一个步骤一个 JSON 文件的方式持久化任务结果。
 *
 * <p>写入时先生成临时文件，再原子替换正式文件，防止进程在写入中途退出后
 * 留下半个 JSON 文件。</p>
 */
@Service
public class FileTaskResultStore implements TaskResultStore {

    private static final Pattern TASK_NO_PATTERN = Pattern.compile("TASK\\d{12}");

    private final Path resultDirectory;

    /** 根据执行器配置确定任务结果目录。 */
    public FileTaskResultStore(AgentProperties properties) {
        this.resultDirectory = Paths.get(properties.getTaskResultDir())
                .toAbsolutePath().normalize();
    }

    /** 将运维任务执行结果持久化到本地文件，供后续上报和失败重试使用。 */
    @Override
    public synchronized void save(TaskResultRecord record) {

        // 1. 检验记录是否合法
        validateRecord(record);
        // 2. 写入本地磁盘
        writeRecord(record);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized TaskResultRecord find(String taskNo, int stepNo) {
        Path file = resultFile(taskNo, stepNo);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return readRecord(file);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<TaskResultRecord> findUnreported() {
        List<TaskResultRecord> results = new ArrayList<>();
        if (!Files.isDirectory(resultDirectory)) {
            return results;
        }

        try (DirectoryStream<Path> files = Files.newDirectoryStream(resultDirectory, "*.json")) {
            for (Path file : files) {
                TaskResultRecord record = readRecord(file);
                if (!record.isReported()) {
                    results.add(record);
                }
            }
            return results;
        } catch (IOException e) {
            throw new IllegalStateException("读取待上报任务结果失败", e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void markReported(TaskResultRecord record) {
        validateRecord(record);
        record.setReported(true);
        record.setReportedTime(System.currentTimeMillis());
        writeRecord(record);
    }

    /** 将完整结果写入临时文件并替换正式文件。 */
    private void writeRecord(TaskResultRecord record) {
        try {
            Files.createDirectories(resultDirectory);
            Path target = resultFile(record.getTaskNo(), record.getStepNo());
            Path temporary = resultDirectory.resolve(target.getFileName() + ".tmp");
            Files.write(temporary, JSON.toJSONString(record).getBytes(StandardCharsets.UTF_8));
            moveReplacing(temporary, target);
        } catch (IOException e) {
            throw new IllegalStateException("保存任务结果到本机磁盘失败", e);
        }
    }

    /** 读取并校验一个已持久化结果文件。 */
    private TaskResultRecord readRecord(Path file) {
        try {
            byte[] content = Files.readAllBytes(file);
            TaskResultRecord record = JSON.parseObject(
                    new String(content, StandardCharsets.UTF_8), TaskResultRecord.class);
            validateRecord(record);
            return record;
        } catch (Exception e) {
            throw new IllegalStateException("任务结果文件损坏: " + file, e);
        }
    }

    /** 构造只能位于结果目录内的安全文件路径。 */
    private Path resultFile(String taskNo, int stepNo) {
        validateIdentity(taskNo, stepNo);
        return resultDirectory.resolve(taskNo + "-" + stepNo + ".json");
    }

    /** 校验结果中用于匹配平台步骤的关键字段。 */
    private void validateRecord(TaskResultRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("任务结果不能为空");
        }

        // 校验任务编号格式及步骤编号是否合法。
        validateIdentity(record.getTaskNo(), record.getStepNo() == null ? 0 : record.getStepNo());

        if (isBlank(record.getAgentId()) || isBlank(record.getOperationType())
                || isBlank(record.getStatus()) || isBlank(record.getResult())) {
            throw new IllegalArgumentException("任务结果关键字段不能为空");
        }
        if (!"SUCCESS".equals(record.getStatus()) && !"FAILED".equals(record.getStatus())) {
            throw new IllegalArgumentException("任务结果状态只能是 SUCCESS 或 FAILED");
        }
    }

    /** 校验文件名中使用的任务编号和步骤序号。 */
    private void validateIdentity(String taskNo, int stepNo) {
        if (taskNo == null || !TASK_NO_PATTERN.matcher(taskNo).matches() || stepNo < 1) {
            throw new IllegalArgumentException("任务编号或步骤序号不合法");
        }
    }

    /** 判断字符串是否为空。 */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 优先使用原子移动，不支持时退化为普通覆盖移动。 */
    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailure) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
