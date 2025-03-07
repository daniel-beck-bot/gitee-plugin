package com.gitee.jenkins.trigger.handler.pull;

import com.gitee.jenkins.cause.CauseData;
import com.gitee.jenkins.cause.GiteeWebHookCause;
import com.gitee.jenkins.gitee.api.GiteeClient;
import com.gitee.jenkins.gitee.api.model.PullRequest;
import com.gitee.jenkins.gitee.hook.model.*;
import com.gitee.jenkins.gitee.hook.model.PullRequestHook;
import com.gitee.jenkins.publisher.GiteeMessagePublisher;
import com.gitee.jenkins.trigger.exception.NoRevisionToBuildException;
import com.gitee.jenkins.trigger.filter.BranchFilter;
import com.gitee.jenkins.trigger.filter.PullRequestLabelFilter;
import com.gitee.jenkins.trigger.handler.AbstractWebHookTriggerHandler;
import com.gitee.jenkins.util.BuildUtil;
import hudson.model.Job;
import hudson.model.Run;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.RevisionParameterAction;
import org.apache.commons.lang.StringUtils;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.gitee.jenkins.cause.CauseDataBuilder.causeData;
import static com.gitee.jenkins.trigger.handler.builder.generated.BuildStatusUpdateBuilder.buildStatusUpdate;
import static com.gitee.jenkins.connection.GiteeConnectionProperty.getClient;

/**
 * @author Robin Müller
 * @author Yashin Luo
 */
class PullRequestHookTriggerHandlerImpl extends AbstractWebHookTriggerHandler<PullRequestHook> implements PullRequestHookTriggerHandler {

    private static final Logger LOGGER = Logger.getLogger(PullRequestHookTriggerHandlerImpl.class.getName());

    private final Collection<State> allowedStates;
    private final boolean skipWorkInProgressPullRequest;
    private final boolean ciSkipFroTestNotRequired;
	private final Collection<Action> allowedActions;
    private final boolean cancelPendingBuildsOnUpdate;

    PullRequestHookTriggerHandlerImpl(Collection<State> allowedStates, boolean skipWorkInProgressPullRequest, boolean cancelPendingBuildsOnUpdate, boolean ciSkipFroTestNotRequired) {
        this(allowedStates, EnumSet.allOf(Action.class), skipWorkInProgressPullRequest, cancelPendingBuildsOnUpdate, ciSkipFroTestNotRequired);
    }

    PullRequestHookTriggerHandlerImpl(Collection<State> allowedStates, Collection<Action> allowedActions, boolean skipWorkInProgressPullRequest, boolean cancelPendingBuildsOnUpdate, boolean ciSkipFroTestNotRequired) {
        this.allowedStates = allowedStates;
        this.allowedActions = allowedActions;
        this.skipWorkInProgressPullRequest = skipWorkInProgressPullRequest;
        this.cancelPendingBuildsOnUpdate = cancelPendingBuildsOnUpdate;
        this.ciSkipFroTestNotRequired = ciSkipFroTestNotRequired;
    }

    @Override
    public void handle(Job<?, ?> job, PullRequestHook hook, boolean ciSkip, boolean skipLastCommitHasBeenBuild, BranchFilter branchFilter, PullRequestLabelFilter pullRequestLabelFilter) {
        PullRequestObjectAttributes objectAttributes = hook.getPullRequest();

        try {
            LOGGER.log(Level.INFO, "request hook  state=" + hook.getState() + ", action = " + hook.getAction() + " pr iid = " + objectAttributes.getNumber() + " hook name = " + hook.getHookName());
            if (isAllowedByConfig(hook)
                && isNotSkipWorkInProgressPullRequest(objectAttributes)) {
                List<String> labelsNames = new ArrayList<>();
                if (hook.getLabels() != null) {
                    for (PullRequestLabel label : hook.getLabels()) {
                        labelsNames.add(label.getTitle());
                    }
                }

                // 若pr不可自动合并则评论至pr
                if (!objectAttributes.isMergeable()) {
                    LOGGER.log(Level.INFO, "This pull request can not be merge");
                    GiteeMessagePublisher publisher = GiteeMessagePublisher.getFromJob(job);
                    GiteeClient client = getClient(job);

                    if (publisher != null && client != null) {
                        PullRequest pullRequest = new PullRequest(objectAttributes);
                        LOGGER.log(Level.INFO, "sending message to gitee.....");
                        client.createPullRequestNote(pullRequest, ":bangbang: This pull request can not be merge! The build will not be triggered. Please manual merge conflict.");
                    }
                    return;
                }

                // 若PR不需要测试，且有设定值，则跳过构建
                if ( ciSkipFroTestNotRequired && !objectAttributes.getNeedTest()) {
                    LOGGER.log(Level.INFO, "Skip because this pull don't need test.");
                    return;
                }

                if (pullRequestLabelFilter.isPullRequestAllowed(labelsNames)) {
                    super.handle(job, hook, ciSkip, skipLastCommitHasBeenBuild, branchFilter, pullRequestLabelFilter);
                }
            }
            else {
                LOGGER.log(Level.INFO, "request is not allow, hook state=" + hook.getState() + ", action = " + hook.getAction());
            }
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "request is not allow, hook ----- #" + hook.toString());
            throw e;
        }

    }

    @Override
    protected boolean isCiSkip(PullRequestHook hook) {
        return hook.getPullRequest() != null
                && hook.getPullRequest().getBody() != null
                && hook.getPullRequest().getBody().contains("[ci-skip]");
    }

    @Override
    protected boolean isCommitSkip(Job<?, ?> project, PullRequestHook hook) {
        PullRequestObjectAttributes objectAttributes = hook.getPullRequest();

        if (objectAttributes != null && objectAttributes.getMergeCommitSha() != null) {
            Run<?, ?> mergeBuild = BuildUtil.getBuildBySHA1IncludingMergeBuilds(project, objectAttributes.getMergeCommitSha());
            if (mergeBuild != null && StringUtils.equals(getTargetBranchFromBuild(mergeBuild), objectAttributes.getTargetBranch())) {
                LOGGER.log(Level.INFO, "Last commit in Pull Request has already been built in build #" + mergeBuild.getNumber());
                return true;
            }
        }
        return false;
    }

    @Override
    protected void cancelPendingBuildsIfNecessary(Job<?, ?> job, PullRequestHook hook) {
        if (!this.cancelPendingBuildsOnUpdate) {
            return;
        }
        if (!hook.getAction().equals(Action.update)) {
            return;
        }
        this.pendingBuildsHandler.cancelPendingBuilds(job, hook.getPullRequest().getSourceProjectId(), hook.getPullRequest().getSourceBranch());
    }

    @Override
    protected String getTargetBranch(PullRequestHook hook) {
        return hook.getPullRequest() == null ? null : hook.getPullRequest().getTargetBranch();
    }

    @Override
    protected String getTriggerType() {
        return "pull request";
    }

    @Override
    protected CauseData retrieveCauseData(PullRequestHook hook) {
        return   causeData()
                .withActionType(CauseData.ActionType.MERGE)
                .withSourceProjectId(hook.getPullRequest().getSourceProjectId())
                .withTargetProjectId(hook.getPullRequest().getTargetProjectId())
                .withBranch(hook.getPullRequest().getSourceBranch())
                .withSourceBranch(hook.getPullRequest().getSourceBranch())
                .withUserName(hook.getPullRequest().getHead().getUser().getName())
                .withUserEmail(hook.getPullRequest().getHead().getUser().getEmail())
                .withSourceRepoHomepage(hook.getPullRequest().getSource().getHomepage())
                .withSourceRepoName(hook.getPullRequest().getSource().getName())
                .withSourceNamespace(hook.getPullRequest().getSource().getNamespace())
                .withSourceRepoUrl(hook.getPullRequest().getSource().getUrl())
                .withSourceRepoSshUrl(hook.getPullRequest().getSource().getSshUrl())
                .withSourceRepoHttpUrl(hook.getPullRequest().getSource().getGitHttpUrl())
                .withPullRequestTitle(hook.getPullRequest().getTitle())
                .withPullRequestDescription(hook.getPullRequest().getBody())
                .withPullRequestId(hook.getPullRequest().getId())
                .withPullRequestIid(hook.getPullRequest().getNumber())
                .withPullRequestState(hook.getState().toString())
                .withMergedByUser(hook.getUser() == null ? null : hook.getUser().getUsername())
                .withPullRequestAssignee(hook.getAssignee() == null ? null : hook.getAssignee().getUsername())
                .withPullRequestTargetProjectId(hook.getPullRequest().getTargetProjectId())
                .withTargetBranch(hook.getPullRequest().getTargetBranch())
                .withTargetRepoName(hook.getPullRequest().getTarget().getName())
                .withTargetNamespace(hook.getPullRequest().getTarget().getNamespace())
                .withTargetRepoSshUrl(hook.getPullRequest().getTarget().getSshUrl())
                .withTargetRepoHttpUrl(hook.getPullRequest().getTarget().getGitHttpUrl())
                .withTriggeredByUser(hook.getPullRequest().getHead().getUser().getName())
                .withLastCommit(hook.getPullRequest().getMergeCommitSha())
                .withTargetProjectUrl(hook.getPullRequest().getTarget().getUrl())
                .withPathWithNamespace(hook.getRepo().getPathWithNamespace())
                .build();
    }

    @Override
    protected RevisionParameterAction createRevisionParameter(PullRequestHook hook, GitSCM gitSCM) throws NoRevisionToBuildException {
        return new RevisionParameterAction(retrieveRevisionToBuild(hook), retrieveUrIish(hook, gitSCM));
    }

    @Override
    protected BuildStatusUpdate retrieveBuildStatusUpdate(PullRequestHook hook) {
        return buildStatusUpdate()
            .withProjectId(hook.getPullRequest().getSourceProjectId())
            .withSha(hook.getPullRequest().getMergeCommitSha())
            .withRef(hook.getPullRequest().getSourceBranch())
            .build();
    }

    private String retrieveRevisionToBuild(PullRequestHook hook) throws NoRevisionToBuildException {
        if (hook.getPullRequest() != null
            && hook.getPullRequest().getMergeReferenceName() != null) {
            return hook.getPullRequest().getMergeReferenceName();
        } else {
            throw new NoRevisionToBuildException();
        }
    }

    private String getTargetBranchFromBuild(Run<?, ?> mergeBuild) {
        GiteeWebHookCause cause = mergeBuild.getCause(GiteeWebHookCause.class);
        return cause == null ? null : cause.getData().getTargetBranch();
    }

	private boolean isAllowedByConfig(PullRequestHook hook) {
		return allowedStates.contains(hook.getState())
        	&& allowedActions.contains(hook.getAction());
	}

	// Gitee 无此状态，暂时屏蔽
    private boolean isNotSkipWorkInProgressPullRequest(PullRequestObjectAttributes objectAttributes) {
//        Boolean workInProgress = objectAttributes.getWorkInProgress();
//        if (skipWorkInProgressPullRequest && workInProgress != null && workInProgress) {
//            LOGGER.log(Level.INFO, "Skip WIP Pull Request #{0} ({1})", toArray(objectAttributes.getNumber(), objectAttributes.getTitle()));
//            return false;
//        }
        return true;
    }
}
