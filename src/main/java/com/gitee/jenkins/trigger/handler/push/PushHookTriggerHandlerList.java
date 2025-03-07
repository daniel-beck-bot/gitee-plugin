package com.gitee.jenkins.trigger.handler.push;

import com.gitee.jenkins.gitee.hook.model.PushHook;
import com.gitee.jenkins.trigger.filter.BranchFilter;
import com.gitee.jenkins.trigger.filter.PullRequestLabelFilter;
import hudson.model.Job;

import java.util.List;

/**
 * @author Robin Müller
 */
class PushHookTriggerHandlerList implements PushHookTriggerHandler {

    private final List<PushHookTriggerHandler> handlers;

    PushHookTriggerHandlerList(List<PushHookTriggerHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void handle(Job<?, ?> job, PushHook hook, boolean ciSkip, boolean skipLastCommitHasBeenBuild, BranchFilter branchFilter, PullRequestLabelFilter pullRequestLabelFilter) {
        for (PushHookTriggerHandler handler : handlers) {
            handler.handle(job, hook, ciSkip, skipLastCommitHasBeenBuild, branchFilter, pullRequestLabelFilter);
        }
    }
}
