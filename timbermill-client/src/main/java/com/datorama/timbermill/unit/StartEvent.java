package com.datorama.timbermill.unit;

import javax.validation.constraints.NotNull;

public class StartEvent extends Event {
    public StartEvent(String taskId, String name, @NotNull LogParams logParams, String primaryId, String parentId) {
        super(taskId, name, logParams, parentId);
        if (primaryId == null){
            this.primaryId = this.taskId;
        } else {
            this.primaryId = primaryId;
        }
    }

    public StartEvent(String name, @NotNull LogParams logParams, String primaryId, String parentId) {
        this(null, name, logParams, primaryId, parentId);
    }

    @Override
    public Task.TaskStatus getStatus(Task.TaskStatus status) {
        if (status == Task.TaskStatus.CORRUPTED_SUCCESS){
            return Task.TaskStatus.SUCCESS;
        }
        else if (status == Task.TaskStatus.CORRUPTED_ERROR){
            return Task.TaskStatus.ERROR;
        }
        else {
            return Task.TaskStatus.UNTERMINATED;
        }
    }

    @Override
    public boolean isStartEvent() {
        return true;
    }
}
