package javaposse.jobdsl.dsl.helpers.triggers

import javaposse.jobdsl.dsl.ContextHelper
import javaposse.jobdsl.dsl.DslContext
import javaposse.jobdsl.dsl.Item
import javaposse.jobdsl.dsl.JobManagement
import javaposse.jobdsl.dsl.Preconditions
import javaposse.jobdsl.dsl.RequiresPlugin
import javaposse.jobdsl.dsl.WithXmlAction
import javaposse.jobdsl.dsl.helpers.common.Threshold
import javaposse.jobdsl.dsl.helpers.triggers.GerritContext.GerritSpec

class TriggerContext extends ItemTriggerContext {
    TriggerContext(JobManagement jobManagement, Item item) {
        super(jobManagement, item)
    }

    /**
     * Polls source control for changes at regular intervals.
     *
     * To configure a multi-line entry, use a single trigger string with entries separated by {@code \n}.
     */
    void scm(String cronString, @DslContext(ScmTriggerContext) Closure scmTriggerClosure = null) {
        Preconditions.checkNotNull(cronString, 'cronString must be specified')

        ScmTriggerContext scmTriggerContext = new ScmTriggerContext()
        ContextHelper.executeInContext(scmTriggerClosure, scmTriggerContext)

        triggerNodes << new NodeBuilder().'hudson.triggers.SCMTrigger' {
            spec cronString
            ignorePostCommitHooks scmTriggerContext.ignorePostCommitHooks
        }
    }

    /**
     * Trigger that runs jobs on push notifications from GitHub.
     *
     * @since 1.16
     */
    @RequiresPlugin(id = 'github')
    void githubPush() {
        triggerNodes << new NodeBuilder().'com.cloudbees.jenkins.GitHubPushTrigger' {
            spec ''
        }
    }

    /**
     * Polls Gerrit for changes.
     */
    @RequiresPlugin(id = 'gerrit-trigger')
    void gerrit(@DslContext(GerritContext) Closure contextClosure = null) {
        // See what they set up in the contextClosure before generating xml
        GerritContext gerritContext = new GerritContext()
        ContextHelper.executeInContext(contextClosure, gerritContext)

        String filePathNodeName = 'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.FilePath'
        String branchNodeName = 'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.Branch'
        String gerritProjectNodeName = 'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.GerritProject'
        String gerritTriggerNodeName = 'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.GerritTrigger'

        NodeBuilder nodeBuilder = new NodeBuilder()
        Node gerritNode = nodeBuilder."$gerritTriggerNodeName" {
            spec ''
            if (gerritContext.projects) {
                gerritProjects {
                    gerritContext.projects.each {
                        GerritSpec project, List<GerritSpec> brancheSpecs, List<String> filePathsSpec ->
                            "$gerritProjectNodeName" {
                                compareType project.type
                                pattern project.pattern
                                branches {
                                    brancheSpecs.each { GerritSpec branch ->
                                        "$branchNodeName" {
                                            compareType branch.type
                                            pattern branch.pattern
                                        }
                                    }
                                }
                                if (filePathsSpec) {
                                    filePaths {
                                        filePathsSpec.each { GerritSpec path ->
                                            "$filePathNodeName" {
                                                compareType path.type
                                                pattern path.pattern
                                            }
                                        }
                                    }
                                }
                            }
                    }
                }
            }
            silentMode false
            escapeQuotes true
            buildStartMessage ''
            buildFailureMessage ''
            buildSuccessfulMessage ''
            buildUnstableMessage ''
            buildNotBuiltMessage ''
            buildUnsuccessfulFilepath ''
            customUrl ''
            if (gerritContext.eventContext.eventShortNames) {
                triggerOnEvents {
                    gerritContext.eventContext.eventShortNames.each {
                        "com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.events.Plugin${it}Event" ''
                    }
                }
            }
            gerritContext.with {
                if (startedVerified != null) {
                    gerritBuildStartedVerifiedValue startedVerified
                }
                if (startedCodeReview != null) {
                    gerritBuildStartedCodeReviewValue startedCodeReview
                }
                if (successfulVerified != null) {
                    gerritBuildSuccessfulVerifiedValue successfulVerified
                }
                if (successfulCodeReview != null) {
                    gerritBuildSuccessfulCodeReviewValue successfulCodeReview
                }
                if (failedVerified != null) {
                    gerritBuildFailedVerifiedValue failedVerified
                }
                if (failedCodeReview != null) {
                    gerritBuildFailedCodeReviewValue failedCodeReview
                }
                if (unstableVerified != null) {
                    gerritBuildUnstableVerifiedValue unstableVerified
                }
                if (unstableCodeReview != null) {
                    gerritBuildUnstableCodeReviewValue unstableCodeReview
                }
                if (notBuiltVerified != null) {
                    gerritBuildNotBuiltVerifiedValue notBuiltVerified
                }
                if (notBuiltCodeReview != null) {
                    gerritBuildNotBuiltCodeReviewValue notBuiltCodeReview
                }
            }
            dynamicTriggerConfiguration false
            triggerConfigURL ''
            triggerInformationAction ''
        }

        // Apply their overrides
        if (gerritContext.configureClosure) {
            WithXmlAction action = new WithXmlAction(gerritContext.configureClosure)
            action.execute(gerritNode)
        }

        triggerNodes << gerritNode
    }

    /**
     * Starts a build on completion of an upstream job, i.e. adds the "Build after other projects are built" trigger.
     *
     * Possible thresholds are {@code 'SUCCESS'}, {@code 'UNSTABLE'} or {@code 'FAILURE'}.
     *
     * @since 1.33
     */
    void upstream(String projects, String threshold = 'SUCCESS') {
        Preconditions.checkNotNullOrEmpty(projects, 'projects must be specified')
        Preconditions.checkArgument(
            Threshold.THRESHOLD_COLOR_MAP.containsKey(threshold),
            "threshold must be one of ${Threshold.THRESHOLD_COLOR_MAP.keySet().join(', ')}"
        )

        triggerNodes << new NodeBuilder().'jenkins.triggers.ReverseBuildTrigger' {
            spec()
            upstreamProjects(projects)
            delegate.threshold {
                name(threshold)
                ordinal(Threshold.THRESHOLD_ORDINAL_MAP[threshold])
                color(Threshold.THRESHOLD_COLOR_MAP[threshold])
                completeBuild(true)
            }
        }
    }

    /**
     * Allows to schedule a build on Jenkins after a job execution on RunDeck.
     *
     * @since 1.33
     */
    @RequiresPlugin(id = 'rundeck', minimumVersion = '3.4')
    void rundeck(@DslContext(RundeckTriggerContext) Closure closure = null) {
        RundeckTriggerContext context = new RundeckTriggerContext()
        ContextHelper.executeInContext(closure, context)

        triggerNodes << new NodeBuilder().'org.jenkinsci.plugins.rundeck.RundeckTrigger' {
            spec()
            filterJobs(context.filterJobs)
            jobsIdentifiers {
                context.jobIdentifiers.each { String jobsIdentifier ->
                    string(jobsIdentifier)
                }
            }
            executionStatuses {
                context.executionStatuses.each { String status ->
                    string(status)
                }
            }
        }
    }

    /**
     * Trigger that runs jobs on push notifications from Bitbucket.
     *
     * @since 1.41
     */
    @RequiresPlugin(id = 'bitbucket', minimumVersion = '1.1.2')
    void bitbucketPush() {
        triggerNodes << new NodeBuilder().'com.cloudbees.jenkins.plugins.BitBucketTrigger' {
            spec()
        }
    }

    /**
     * Trigger that runs jobs on push notifications from GitLab.
     *
     * @since 1.42
     */
    @RequiresPlugin(id = 'gitlab-plugin', minimumVersion = '1.1.28')
    void gitlabPush(@DslContext(GitLabTriggerContext) Closure closure) {
        GitLabTriggerContext context = new GitLabTriggerContext()
        ContextHelper.executeInContext(closure, context)

        triggerNodes << new NodeBuilder().'com.dabsquared.gitlabjenkins.GitLabPushTrigger' {
            spec()
            triggerOnPush(context.buildOnPushEvents)
            triggerOnMergeRequest(context.buildOnMergeRequestEvents)
            triggerOpenMergeRequestOnPush(context.rebuildOpenMergeRequest)
            ciSkip(context.enableCiSkip)
            setBuildDescription(context.setBuildDescription)
            addNoteOnMergeRequest(context.addNoteOnMergeRequest)
            addCiMessage(context.useCiFeatures)
            addVoteOnMergeRequest(context.addVoteOnMergeRequest)
            allowAllBranches(context.allowAllBranches)
            includeBranchesSpec(context.includeBranches ?: '')
            excludeBranchesSpec(context.excludeBranches ?: '')
            acceptMergeRequestOnSuccess(context.acceptMergeRequestOnSuccess)
        }
    }
}
