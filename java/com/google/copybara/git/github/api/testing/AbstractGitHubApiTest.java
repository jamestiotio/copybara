/*
 * Copyright (C) 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.git.github.api.testing;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static com.google.copybara.git.github.api.GitHubApi.PullRequestListParams.DirectionFilter.ASC;
import static com.google.copybara.git.github.api.GitHubApi.PullRequestListParams.SortFilter.CREATED;
import static com.google.copybara.testing.git.GitTestUtil.createValidator;
import static com.google.copybara.testing.git.GitTestUtil.getResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.git.github.api.AddAssignees;
import com.google.copybara.git.github.api.AddLabels;
import com.google.copybara.git.github.api.AuthorAssociation;
import com.google.copybara.git.github.api.CheckRun;
import com.google.copybara.git.github.api.CheckSuite;
import com.google.copybara.git.github.api.CombinedStatus;
import com.google.copybara.git.github.api.CommentBody;
import com.google.copybara.git.github.api.CreatePullRequest;
import com.google.copybara.git.github.api.CreateReleaseRequest;
import com.google.copybara.git.github.api.CreateStatusRequest;
import com.google.copybara.git.github.api.GitHubApi;
import com.google.copybara.git.github.api.GitHubApi.PullRequestListParams;
import com.google.copybara.git.github.api.GitHubApiException;
import com.google.copybara.git.github.api.GitHubApiException.ResponseCode;
import com.google.copybara.git.github.api.GitHubApiTransport;
import com.google.copybara.git.github.api.GitHubCommit;
import com.google.copybara.git.github.api.Installation;
import com.google.copybara.git.github.api.Issue;
import com.google.copybara.git.github.api.Issue.CreateIssueRequest;
import com.google.copybara.git.github.api.IssueComment;
import com.google.copybara.git.github.api.IssuesAndPullRequestsSearchResults;
import com.google.copybara.git.github.api.IssuesAndPullRequestsSearchResults.IssuesAndPullRequestsSearchResult;
import com.google.copybara.git.github.api.Label;
import com.google.copybara.git.github.api.Organization;
import com.google.copybara.git.github.api.PullRequest;
import com.google.copybara.git.github.api.PullRequestComment;
import com.google.copybara.git.github.api.Ref;
import com.google.copybara.git.github.api.Release;
import com.google.copybara.git.github.api.Review;
import com.google.copybara.git.github.api.Status;
import com.google.copybara.git.github.api.Status.State;
import com.google.copybara.git.github.api.UpdatePullRequest;
import com.google.copybara.git.github.api.UpdateReferenceRequest;
import com.google.copybara.git.github.api.User;
import com.google.copybara.git.github.api.UserPermissionLevel;
import com.google.copybara.git.github.api.UserPermissionLevel.GitHubUserPermission;
import com.google.copybara.profiler.LogProfilerListener;
import com.google.copybara.profiler.Profiler;
import com.google.copybara.testing.git.GitTestUtil.JsonValidator;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import net.starlark.java.eval.StarlarkInt;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;

/**
 * Base test to run the same tests on various implementations of the GitHubApiTransport
 */
public abstract class AbstractGitHubApiTest {

  protected GitHubApi api;

  @Captor
  protected ArgumentCaptor<ImmutableListMultimap<String, String>> headerCaptor;
  protected Profiler profiler;

  @Before
  public void setUpFramework() throws Exception {
    profiler = new Profiler(Ticker.systemTicker());
    profiler.init(ImmutableList.of(new LogProfilerListener()));
    api = new GitHubApi(getTransport(), profiler);
  }

  public abstract GitHubApiTransport getTransport() throws Exception;

  public abstract void trainMockGetWithHeaders(String apiPath, byte[] response,
      ImmutableMap<String, String> headers, int status);

  private void trainMockGet(String apiPath, byte[] response) {
    trainMockGetWithHeaders(apiPath, response, ImmutableMap.of(), /*status=*/200);
  }

  public abstract void trainMockPost(String apiPath, Predicate<String> validator, byte[] response);

  public abstract void trainMockDelete(String apiPath, Predicate<String> validator, int statusCode);

  @Test
  public void testGetPulls() throws Exception {
    checkGetPulls("/repos/example/project/pulls?per_page=100", PullRequestListParams.DEFAULT);

    checkGetPulls("/repos/example/project/pulls?per_page=100&head=foo:bar",
        PullRequestListParams.DEFAULT.withHead("foo:bar"));

    checkGetPulls("/repos/example/project/pulls?per_page=100"
            + "&head=user:branch&base=the_base&sort=created&direction=asc",
        PullRequestListParams.DEFAULT
            .withHead("user:branch")
            .withBase("the_base")
            .withDirection(ASC)
            .withSort(CREATED));
  }

  private void checkGetPulls(String expectedUrl, PullRequestListParams params)
      throws IOException, RepoException, ValidationException {
    trainMockGet(expectedUrl, getResource("pulls_testdata.json"));
    ImmutableList<PullRequest> pullRequests = api.getPullRequests(
        "example/project", params);

    assertThat(pullRequests).hasSize(2);
    assertThat(pullRequests.get(0).getNumber()).isEqualTo(12345);
    assertThat(pullRequests.get(1).getNumber()).isEqualTo(12346);

    assertThat(pullRequests.get(0).getState()).isEqualTo("open");
    assertThat(pullRequests.get(1).getState()).isEqualTo("closed");

    assertThat(pullRequests.get(0).getTitle()).isEqualTo("[TEST] example pull request one");
    assertThat(pullRequests.get(1).getTitle()).isEqualTo("Another title");

    assertThat(pullRequests.get(0).getBody()).isEqualTo("Example body.\r\n");
    assertThat(pullRequests.get(1).getBody()).isEqualTo("Some text\r\n"
        + "And even more text\r\n");

    assertThat(pullRequests.get(0).getHead().getLabel())
        .isEqualTo("googletestuser:example-branch");
    assertThat(pullRequests.get(0).getHead().getRef()).isEqualTo("example-branch");
    assertThat(pullRequests.get(0).getHead().getSha()).isEqualTo(
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

    assertThat(pullRequests.get(1).getHead().getLabel())
        .isEqualTo("anothergoogletestuser:another-branch");
    assertThat(pullRequests.get(1).getHead().getRef()).isEqualTo("another-branch");
    assertThat(pullRequests.get(1).getHead().getSha()).isEqualTo(
        "dddddddddddddddddddddddddddddddddddddddd");
  }

  @Test
  public void testGetPull() throws Exception {
    trainMockGet(
        "/repos/example/project/pulls/12345", getResource("pulls_12345_testdata.json"));
    PullRequest pullRequest = api.getPullRequest("example/project", 12345);

    assertThat(pullRequest.getNumber()).isEqualTo(12345);
    assertThat(pullRequest.getState()).isEqualTo("open");
    assertThat(pullRequest.getUser().getLogin()).isEqualTo("googletestuser");
    assertThat(pullRequest.getAssignee().getLogin()).isEqualTo("octocat");
    assertThat(pullRequest.getAssignees()).hasSize(1);
    assertThat(pullRequest.getRequestedReviewers()).hasSize(2);
    assertThat(pullRequest.getRequestedReviewers().get(0).getLogin())
        .isEqualTo("some_requested_reviewer");
    assertThat(pullRequest.getRequestedReviewers().get(1).getLogin())
        .isEqualTo("other_requested_reviewer");
    assertThat(pullRequest.getAssignees().get(0).getLogin()).isEqualTo("octocat");
    assertThat(pullRequest.getTitle()).isEqualTo("[TEST] example pull request one");
    assertThat(pullRequest.getBody()).isEqualTo("Example body.\r\n");
    assertThat(pullRequest.getHead().getLabel())
        .isEqualTo("googletestuser:example-branch");
    assertThat(pullRequest.getHead().getRef()).isEqualTo("example-branch");
    assertThat(pullRequest.getHead().getSha()).isEqualTo(
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    assertThat(pullRequest.getMerged()).isFalse();
    assertThat(pullRequest.getCommits()).isEqualTo(StarlarkInt.of(3));
  }

  @Test
  public void testUpdatePullRequest() throws Exception {
    JsonValidator<UpdatePullRequest> validator = createValidator(UpdatePullRequest.class,
        (request) ->
            request.getTitle().equals("title")
                && request.getBody().equals("body")
                && request.getState().equals(UpdatePullRequest.State.CLOSED));

    trainMockPost(
        "/repos/example/project/pulls/12345", validator, getResource("pulls_12345_testdata.json"));

    api.updatePullRequest("example/project", 12345,
        new UpdatePullRequest("title", "body", UpdatePullRequest.State.CLOSED));

    assertThat(validator.wasCalled()).isTrue();
  }

  @Test
  public void testGetUserPermissionLevel() throws Exception {
    trainMockGet("/repos/example/project/collaborators/foo/permission",
        getResource("user_permission_level_testdata.json"));
    UserPermissionLevel permissionLevel =
        api.getUserPermissionLevel("example/project", "foo");
    assertThat(permissionLevel.getPermission()).isEqualTo(GitHubUserPermission.ADMIN);
  }

  @Test
  public void testAuthenticatedUser() throws Exception {
    trainMockGet(
        "/user", getResource("get_authenticated_user_response_testdata.json"));
    User user =  api.getAuthenticatedUser();
    assertThat(user.getLogin()).isEqualTo("googletestuser");

  }

  @Test
  public void testGetPullFail() throws Exception {
    try {
      api.getPullRequest("example/project", 12345);
      fail();
    } catch (ValidationException e) {
      assertThat(e).hasMessageThat().contains("Pull Request not found");
      GitHubApiException cause = (GitHubApiException) e.getCause();
      assertThat(cause.getError().getMessage()).isNotEmpty();
      assertThat(cause.getError().getDocumentationUrl()).isNotEmpty();
      assertThat(cause.getRawError()).isNotEmpty();
      assertThat(cause.getHttpCode()).isEqualTo(404);
      assertThat(cause.getResponseCode()).isEqualTo(ResponseCode.NOT_FOUND);
    }
  }

  @Test
  public void testGetPullReviews() throws Exception {
    trainMockGetWithHeaders("/repos/octocat/Hello-World/pulls/12/reviews?per_page=100",
        getResource("pulls_12345_reviews_testdata.json"),
        ImmutableMap.of("Link", ""
            + "<https://api.github.com/repositories/123/pulls?per_page=100&page=2>; rel=\"next\", "
            + "<https://api.github.com/repositories/123/pulls?per_page=100&page=3>; rel=\"last\", "
            + "<https://api.github.com/repositories/123/pulls?per_page=100&page=1>; rel=\"first\""
        ), 200);

    trainMockGetWithHeaders("/repositories/123/pulls?per_page=100&page=2",
        getResource("pulls_12345_reviews_testdata.json"),
        ImmutableMap.of("Link", ""
            + "<https://api.github.com/repositories/123/pulls?per_page=100&page=1>; rel=\"prev\","
            + "<https://api.github.com/repositories/123/pulls?per_page=100&page=3>; rel=\"next\", "
            + "<https://api.github.com/repositories/123/pulls?per_page=100&page=3>; rel=\"last\", "
            + "<https://api.github.com/repositories/123/pulls?per_page=100&page=1>; rel=\"first\""
        ), 200);

    trainMockGetWithHeaders("/repositories/123/pulls?per_page=100&page=3",
        getResource("pulls_12345_reviews_testdata.json"),
        ImmutableMap.of("Link", ""
            + "<https://api.github.com/repositories/123/pulls?per_page=100&page=2>; rel=\"prev\","
            + "<https://api.github.com/repositories/123/pulls?per_page=100&page=3>; rel=\"last\", "
            + "<https://api.github.com/repositories/123/pulls?per_page=100&page=1>; rel=\"first\""
        ), 200);
    List<Review> reviews = api.getReviews("octocat/Hello-World", 12);

    assertThat(reviews).hasSize(3);
    for (Review review : reviews) {
      assertThat(review.getBody()).isEqualTo("Here is the body for the review.");
      assertThat(review.getId()).isEqualTo(80L);
      assertThat(review.getUser().getId()).isEqualTo(1L);
      assertThat(review.getUser().getLogin()).isEqualTo("octocat");
      assertThat(review.getState()).isEqualTo("APPROVED");
      assertThat(review.isApproved()).isTrue();
      assertThat(review.getCommitId())
          .isEqualTo("ecdd80bb57125d7ba9641ffaa4d7d2c19d3f3091");
    }
  }

  @Test
  public void testGetLsRemote() throws Exception {
    trainMockGet(
        "/repos/copybara-test/copybara/git/refs?per_page=100",
        getResource("lsremote_testdata.json"));
    ImmutableList<Ref> refs = api.getLsRemote("copybara-test/copybara");

    assertThat(refs).hasSize(3);
    assertThat(refs.get(0).toString()).contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    assertThat(refs.get(0).toString()).contains("refs/heads/master");
    assertThat(refs.get(0).getRef()).isEqualTo("refs/heads/master");
    assertThat(refs.get(0).getUrl())
        .isEqualTo("https://api.github.com/repos/copybara-test/copybara/git/refs/heads/master");
    assertThat(refs.get(0).getSha()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    assertThat(refs.get(1).getRef()).isEqualTo("refs/pull/1/head");
    assertThat(refs.get(1).getSha()).isEqualTo("1234567890123456789012345678901234567890");
    assertThat(refs.get(2).getRef()).isEqualTo("refs/pull/1/merge");
    assertThat(refs.get(2).getSha()).isEqualTo("abcdefabcdefabcdefabcdefabcdefabcdefabcd");
  }

  @Test
  public void testGetLsRemote_empty() throws Exception {
    trainMockGetWithHeaders("/repos/copybara-test/copybara/git/refs?per_page=100",
        ("{\n"
            + "  \"message\": \"Git Repository is empty.\",\n"
            + "  \"documentation_url\":"
            + " \"https://developer.github.com/v3/git/refs/#get-all-references\"\n"
            + "}").getBytes(UTF_8), ImmutableMap.of(),
        409 // Http conflict
    );
    ImmutableList<Ref> refs = api.getLsRemote("copybara-test/copybara");

    assertThat(refs).isEmpty();
  }

  @Test
  public void testGetLsRemote_fail() throws Exception {
    trainMockGetWithHeaders("/repos/copybara-test/copybara/git/refs?per_page=100",
        ("{\n"
            + "  \"message\": \"Whatever you are looking for doesn't exist!!\"\n"
            + "}").getBytes(UTF_8), ImmutableMap.of(),
        404);
    try {
      api.getLsRemote("copybara-test/copybara");
      fail();
    } catch (GitHubApiException e) {
      assertThat(e.getResponseCode()).isEqualTo(ResponseCode.NOT_FOUND);
    }
  }

  @Test
  public void testCreatePullRequest() throws Exception {
    trainMockPost(
        "/repos/example/project/pulls", createValidator(TestCreatePullRequest.class,
            (cpr) ->
                cpr.getTitle().equals("title")
                && cpr.getBase().equals("aabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                && cpr.getBody().equals("[TEST] example pull request one")
                && cpr.getHead().equals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")),
        getResource("pulls_12345_testdata.json"));
    // The test does not actually use the data in the CreatePullRequest
    PullRequest pullRequest =
        api.createPullRequest(
            "example/project",
            new CreatePullRequest(
                "title",
                "[TEST] example pull request one",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "aabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                false));

    assertThat(pullRequest.getNumber()).isEqualTo(12345);
    assertThat(pullRequest.getState()).isEqualTo("open");
    assertThat(pullRequest.getTitle()).isEqualTo("[TEST] example pull request one");
  }

  @Test
  public void testGetIssue() throws Exception {
    trainMockGet(
        "/repos/example/project/issues/12345", getResource("issues_12345_testdata.json"));
    Issue issue = api.getIssue("example/project", 12345);

    assertThat(issue.getNumber()).isEqualTo(12345);
    assertThat(issue.getUser().getLogin()).isEqualTo("googletestuser");
    assertThat(issue.getAssignee().getLogin()).isEqualTo("octocat");
    assertThat(issue.getAssignees()).hasSize(1);
    assertThat(issue.getAssignees().get(0).getLogin()).isEqualTo("octocat");
    assertThat(issue.getState()).isEqualTo("open");
    assertThat(issue.getTitle()).isEqualTo("[TEST] example pull request one");
    assertThat(issue.getBody()).isEqualTo("Example body.\r\n");
    assertThat(Lists.transform(issue.getLabels(), Label::getName))
        .containsExactly("cla: yes");
  }

  @Test
  public void testCreateIssue() throws Exception {
    trainMockPost(
        "/repos/example/project/issues", createValidator(TestCreateIssueRequest.class,
            (ci) ->
                ci.getTitle().equals("[TEST] example pull request one")
                    && ci.getBody().equals("Example body.\n")
                    && ci.getAssignees().equals(ImmutableList.of("foo", "bar"))),

        getResource("issues_12345_testdata.json"));
    Issue issue = api.createIssue("example/project",
        new CreateIssueRequest("[TEST] example pull request one", "Example body.\n",
            ImmutableList.of("foo", "bar")));

    assertThat(issue.getNumber()).isEqualTo(12345);
    assertThat(issue.getState()).isEqualTo("open");
    assertThat(issue.getTitle()).isEqualTo("[TEST] example pull request one");
    assertThat(issue.getBody()).isEqualTo("Example body.\r\n");
  }

  @Test
  public void testCreateStatus() throws Exception {
    trainMockPost("/repos/octocat/Hello-World/statuses/6dcb09b5b57875f334f61aebed695e2e4193db5e",
        createValidator(TestCreateStatusRequest.class,
            csr -> csr.getContext().equals("continuous-integration/jenkins")
                && csr.getState().equals(State.SUCCESS)),
        getResource("create_status_response_testdata.json"));

    Status response = api.createStatus("octocat/Hello-World",
        "6dcb09b5b57875f334f61aebed695e2e4193db5e",
        new CreateStatusRequest(State.SUCCESS, "https://ci.example.com/1000/output",
            "Build has completed successfully", "continuous-integration/jenkins"));

    assertThat(response.getContext()).isEqualTo("continuous-integration/jenkins");
    assertThat(response.getTargetUrl()).isEqualTo("https://ci.example.com/1000/output");
    assertThat(response.getDescription()).isEqualTo("Build has completed successfully");
    assertThat(response.getState()).isEqualTo(State.SUCCESS);
    assertThat(response.getCreator()).isNotNull();
    assertThat(response.getCreator().getLogin()).isEqualTo("octocat");
  }

  @Test
  public void testUpdateReference() throws Exception {
    trainMockPost(
        "/repos/octocat/Hello-World/git/refs/heads/test",
        createValidator(
            TestUpdateReferenceRequest.class,
            urr ->
                urr.getSha1().equals("6dcb09b5b57875f334f61aebed695e2e4193db5e")
                    && urr.getForce()),
        getResource("update_reference_response_testdata.json"));

    Ref response = api.updateReference("octocat/Hello-World",
        "refs/heads/test",
        new UpdateReferenceRequest("6dcb09b5b57875f334f61aebed695e2e4193db5e", true));

    assertThat(response.getRef()).isEqualTo("refs/heads/test");
    assertThat(response.getSha()).isEqualTo("6dcb09b5b57875f334f61aebed695e2e4193db5e");
    assertThat(response.getUrl()).isEqualTo(
        "https://api.github.com/repos/octocat/Hello-World/git/refs/heads/test");
  }

  @Test
  public void testDeleteRef() throws Exception {
    AtomicBoolean called = new AtomicBoolean(false);
    trainMockDelete("/repos/octocat/Hello-World/git/refs/heads/test",
        s -> {
          called.set(true);
          return true;
        }, 202);

    api.deleteReference("octocat/Hello-World", "refs/heads/test");
    assertThat(called.get()).isTrue();
  }

  @Test
  public void testDeleteRefFail() throws Exception {

    trainMockDelete("/repos/octocat/Hello-World/git/refs/heads/test", s -> true, 404);

    try {
      api.deleteReference("octocat/Hello-World", "refs/heads/test");
      fail();
    } catch (GitHubApiException e) {
      assertThat(e.getResponseCode()).isEqualTo(ResponseCode.NOT_FOUND);
    }
  }

  @Test
  public void testGetReference() throws Exception {
    trainMockGet(
        "/repos/octocat/Hello-World/git/refs/heads/g3",
        getResource("get_reference_response_testdata.json"));

    Ref response = api.getReference("octocat/Hello-World", "refs/heads/g3");

    assertThat(response.getRef()).isEqualTo("refs/heads/g3");
    assertThat(response.getSha()).isEqualTo("9a2f372a62761ac378a62935c44cfcb9695d0661");
    assertThat(response.getUrl()).isEqualTo(
        "https://api.github.com/repos/octocat/Hello-World/git/refs/heads/g3");
  }

  @Test
  public void testInvalidParameterWhenGetReference() throws Exception {
    try {
      api.getReference("octocat/Hello-World", "heads/g3");
      fail();
    } catch (ValidationException e) {
      assertThat(e).hasMessageThat().contains("Ref must start with \"refs/\"");
    }
  }

  @Test
  public void testGetAllReferences() throws Exception {
    trainMockGet(
        "/repos/octocat/Hello-World/git/refs?per_page=100",
        getResource("get_all_references_response_testdata.json"));

    ImmutableList<Ref> response = api.getReferences("octocat/Hello-World");

    assertThat(response.get(0).getRef()).isEqualTo("refs/heads/g3");
    assertThat(response.get(0).getSha()).isEqualTo("9a2f372a62761ac378a62935c44cfcb9695d0661");
    assertThat(response.get(0).getUrl()).isEqualTo(
        "https://api.github.com/repos/octocat/Hello-World/git/refs/heads/g3");
    assertThat(response.get(1).getRef()).isEqualTo("refs/heads/master");
    assertThat(response.get(1).getSha()).isEqualTo("9a2f372a62761ac378a62935c44cfcb9695d0661");
    assertThat(response.get(1).getUrl()).isEqualTo(
        "https://api.github.com/repos/octocat/Hello-World/git/refs/heads/master");
  }


  @Test
  public void testGetCombinedStatus() throws Exception {
    trainMockGet(
        "/repos/octocat/Hello-World/commits/6dcb09b5b57875f334f61aebed695e2e4193db5e"
            + "/status?per_page=100",
        getResource("get_combined_status_testdata.json"));

    CombinedStatus response = api.getCombinedStatus("octocat/Hello-World",
        "6dcb09b5b57875f334f61aebed695e2e4193db5e");

    assertThat(response.getSha()).isEqualTo("6dcb09b5b57875f334f61aebed695e2e4193db5e");
    assertThat(response.getStatuses()).hasSize(2);
    assertThat(response.getState()).isEqualTo(State.SUCCESS);
    assertThat(response.getTotalCount()).isEqualTo(2);

    assertThat(response.getStatuses().get(0).getContext())
        .isEqualTo("continuous-integration/jenkins");
    assertThat(response.getStatuses().get(0).getTargetUrl())
        .isEqualTo("https://ci.example.com/1000/output");
    assertThat(response.getStatuses().get(0).getDescription())
        .isEqualTo("Build has completed successfully");
    assertThat(response.getStatuses().get(0).getState()).isEqualTo(State.SUCCESS);
  }

  @Test
  public void testGetPullRequestComment() throws Exception {
    trainMockGet(
        "/repos/example/project/pulls/comments/12345",
        getResource("pulls_comment_12345_testdata.json"));
    PullRequestComment pullRequestComment = api.getPullRequestComment("example/project", 12345);

    assertThat(pullRequestComment.getId()).isEqualTo(12345);
    assertThat(pullRequestComment.getPosition()).isEqualTo(13);
    assertThat(pullRequestComment.getOriginalPosition()).isEqualTo(13);
    assertThat(pullRequestComment.getPath()).contains("git/GitEnvironment.java");
    assertThat(pullRequestComment.getUser().getLogin()).isEqualTo("googletestuser");
    assertThat(pullRequestComment.getCommitId())
        .isEqualTo("228ed14f89c19caed87717a8a53392f58c3a24f9");
    assertThat(pullRequestComment.getOriginalCommitId())
        .isEqualTo("7a8d55973a82b250e8c206673b2ae1e6bacb97d0");
    assertThat(pullRequestComment.getBody()).isEqualTo("This needs to be fixed.");
    assertThat(pullRequestComment.getDiffHunk())
        .isEqualTo(
            "@@ -36,11 +35,16 @@ public GitEnvironment(Map<String, String> environment) {\n"
                + "   }\n"
                + " \n"
                + "   public ImmutableMap<String, String> getEnvironment() {\n"
                + "-    Map<String, String> env = Maps.newHashMap(environment);\n"
                + "+    ImmutableMap.Builder<String, String> env = ImmutableMap.builder();");
    assertThat(pullRequestComment.getCreatedAt())
        .isEqualTo(ZonedDateTime.parse("2019-06-21T20:20:20Z"));
    assertThat(pullRequestComment.getUpdatedAt())
        .isEqualTo(ZonedDateTime.parse("2019-07-18T15:32:41Z"));
  }

  @Test
  public void testGetPullRequestComment_notFound() throws RepoException {
    try {
      api.getPullRequestComment("example/project", 12345);
      fail();
    } catch (ValidationException expected) {
      // expected
    }
  }

  @Test
  public void testGetPullRequestComments() throws Exception {
    trainMockGet(
        "/repos/example/project/pulls/12345/comments?per_page=100",
        getResource("pulls_comments_12345_testdata.json"));
    ImmutableList<PullRequestComment> comments =
        api.getPullRequestComments("example/project", 12345);
    assertThat(comments).hasSize(2);
  }

  @Test
  public void testGetPullRequestComments_notFound() throws Exception {
    try {
      api.getPullRequestComments("example/project", 12345);
      fail();
    } catch (ValidationException expected) {
      // expected
    }
  }

  @Test
  public void test_getCheckRuns_success() throws Exception {
    trainMockGet(
        "/repos/example/project/commits/12345/check-runs?per_page=100",
        getResource("get_check_runs_testdata.json"));
    ImmutableList<CheckRun> checkRuns =
        api.getCheckRuns("example/project", "12345");
    assertThat(checkRuns).hasSize(1);
    assertThat(checkRuns.get(0).getStatus()).isEqualTo("completed");
    assertThat(checkRuns.get(0).getConclusion()).isEqualTo("neutral");
    assertThat(checkRuns.get(0).getDetailUrl()).isEqualTo("https://example.com");
    assertThat(checkRuns.get(0).getApp().getId()).isEqualTo(1);
    assertThat(checkRuns.get(0).getApp().getName()).isEqualTo("Octocat App");
    assertThat(checkRuns.get(0).getApp().getSlug()).isEqualTo("octoapp");
  }

  @Test
  public void test_getCheckRuns_pagination() throws Exception {

    trainMockGetWithHeaders("/repos/example/project/commits/12345/check-runs?per_page=100",
        getResource("get_check_runs_testdata.json"),
        ImmutableMap.of("Link", ""
            + "<https://api.github.com/repos/example/project/commits/12345/check-runs?per_page=100"
            + "&page=2>; rel=\"next\", "
            + "<https://api.github.com/repos/example/project/commits/12345/check-runs?per_page=100"
            + "&page=3>; rel=\"last\", "
            + "<https://api.github.com/repos/example/project/commits/12345/check-runs?per_page=100"
            + "&page=1>; rel=\"first\""
        ), 200);
    trainMockGetWithHeaders("/repos/example/project/commits/12345/check-runs?per_page=100&page=2",
        getResource("get_check_runs_testdata.json"),
        ImmutableMap.of("Link", ""
            + "<https://api.github.com/repos/example/project/commits/12345/check-runs?per_page=100"
            + "&page=1>; rel=\"prev\","
            + "<https://api.github.com/repos/example/project/commits/12345/check-runs?per_page=100"
            + "&page=3>; rel=\"next\", "
            + "<https://api.github.com/repos/example/project/commits/12345/check-runs?per_page=100"
            + "&page=3>; rel=\"last\", "
            + "<https://api.github.com/repos/example/project/commits/12345/check-runs?per_page=100"
            + "&page=1>; rel=\"first\""
        ), 200);
    trainMockGetWithHeaders("/repos/example/project/commits/12345/check-runs?per_page=100&page=3",
        getResource("get_check_runs_testdata.json"),
        ImmutableMap.of("Link", ""
            + "<https://api.github.com/repos/example/project/commits/12345/check-runs?per_page=100"
            + "&page=2>; rel=\"prev\","
            + "<https://api.github.com/repos/example/project/commits/12345/check-runs?per_page=100"
            + "&page=3>; rel=\"last\", "
            + "<https://api.github.com/repos/example/project/commits/12345/check-runs?per_page=100"
            + "&page=1>; rel=\"first\""
        ), 200);

    ImmutableList<CheckRun> checkRuns = api.getCheckRuns("example/project", "12345");
    assertThat(checkRuns).hasSize(3);
  }
  @Test
  public void test_getCheckSuites_success() throws Exception {
    trainMockGet(
        "/repos/example/project/commits/12345/check-suites?per_page=100",
        getResource("get_check_suites_testdata.json"));
    ImmutableList<CheckSuite> checkSuites =
        api.getCheckSuites("example/project", "12345");
    assertThat(checkSuites).hasSize(1);
    assertThat(checkSuites.get(0).getStatus()).isEqualTo("completed");
    assertThat(checkSuites.get(0).getConclusion()).isEqualTo("neutral");
    assertThat(checkSuites.get(0).getId()).isEqualTo(StarlarkInt.of(5));
    assertThat(checkSuites.get(0).getApp().getId()).isEqualTo(1);
    assertThat(checkSuites.get(0).getApp().getName()).isEqualTo("Octocat App");
    assertThat(checkSuites.get(0).getApp().getSlug()).isEqualTo("octoapp");
  }

  @Test
  public void test_getCheckSuites_pagination() throws Exception {
    trainMockGetWithHeaders(
        "/repos/example/project/commits/12345/check-suites?per_page=100",
        getResource("get_check_suites_testdata.json"),
        ImmutableMap.of(
            "Link",
            "<https://api.github.com/repos/example/project/commits/12345/check-suites?per_page=100&page=2>;"
                + " rel=\"next\", "
                + "<https://api.github.com/repos/example/project/commits/12345/check-suites?per_page=100&page=3>;"
                + " rel=\"last\", "
                + "<https://api.github.com/repos/example/project/commits/12345/check-suites?per_page=100&page=1>;"
                + " rel=\"first\""),
        200);
    trainMockGetWithHeaders(
        "/repos/example/project/commits/12345/check-suites?per_page=100&page=2",
        getResource("get_check_suites_testdata.json"),
        ImmutableMap.of(
            "Link",
            "<https://api.github.com/repos/example/project/commits/12345/check-suites?per_page=100&page=1>;"
                + " rel=\"prev\","
                + "<https://api.github.com/repos/example/project/commits/12345/check-suites?per_page=100&page=3>;"
                + " rel=\"next\", "
                + "<https://api.github.com/repos/example/project/commits/12345/check-suites?per_page=100&page=3>;"
                + " rel=\"last\", "
                + "<https://api.github.com/repos/example/project/commits/12345/check-suites?per_page=100&page=1>;"
                + " rel=\"first\""),
        200);
    trainMockGetWithHeaders(
        "/repos/example/project/commits/12345/check-suites?per_page=100&page=3",
        getResource("get_check_suites_testdata.json"),
        ImmutableMap.of(
            "Link",
            "<https://api.github.com/repos/example/project/commits/12345/check-suites?per_page=100&page=2>;"
                + " rel=\"prev\","
                + "<https://api.github.com/repos/example/project/commits/12345/check-suites?per_page=100&page=3>;"
                + " rel=\"last\", "
                + "<https://api.github.com/repos/example/project/commits/12345/check-suites?per_page=100&page=1>;"
                + " rel=\"first\""),
        200);

    ImmutableList<CheckSuite> checkSuites = api.getCheckSuites("example/project", "12345");
    assertThat(checkSuites).hasSize(3);
  }

   @Test
  public void testGetPullRequestComments_empty() throws Exception {
    trainMockGet("/repos/example/project/pulls/12345/comments?per_page=100", "[]".getBytes(UTF_8));
    assertThat(api.getPullRequestComments("example/project", 12345)).isEmpty();
  }

  @Test
  public void testCommit() throws Exception {
    trainMockGet(
        "/repos/octocat/Hello-World/commits/604aa8e189a6fee605140ebbe4a3c34ad24619d1",
        // normally we use GH documentation examples but the docs are different of the actual
        // response
        getResource("commit_response_testdata.json"));

    GitHubCommit r = api.getCommit("octocat/Hello-World",
        "604aa8e189a6fee605140ebbe4a3c34ad24619d1");

    assertThat(r.getSha()).isEqualTo("604aa8e189a6fee605140ebbe4a3c34ad24619d1");
    assertThat(r.getAuthor().getLogin()).isEqualTo("copybara-author");
    assertThat(r.getCommitter().getLogin()).isEqualTo("copybara-committer");
    assertThat(r.getHtmlUrl()).isEqualTo(
        "https://github.com/google/copybara/commit/604aa8e189a6fee605140ebbe4a3c34ad24619d1");
    assertThat(r.getCommit().getMessage()).isEqualTo(""
        + "Temporal fix to the CI\n"
        + "\n"
        + "We use this deprecated flag until we migrate to the new\n"
        + "non-native rule.\n"
        + "\n"
        + "Will properly fix on Monday.\n"
        + "\n"
        + "Change-Id: Ia3c35b8ece932b94e0aa4c7a28bd16d35a260970");
    assertThat(r.getCommit().getAuthor().getName()).isEqualTo("The Author");
    assertThat(r.getCommit().getAuthor().getEmail()).isEqualTo("theauthor@example.com");
    assertThat(r.getCommit().getAuthor().getDate())
        .isEqualTo(ZonedDateTime.parse("2018-12-07T23:36:45Z"));

    assertThat(r.getCommit().getCommitter().getName()).isEqualTo("The Committer");
    assertThat(r.getCommit().getCommitter().getEmail()).isEqualTo("thecommitter@example.com");
    assertThat(r.getCommit().getCommitter().getDate())
        .isEqualTo(ZonedDateTime.parse("2018-11-07T23:36:45Z"));
  }

  @Test
  public void testAddLabel() throws Exception {
    ImmutableList<String> labels = ImmutableList.of("my_label1", "my_label2");
    trainMockPost("/repos/example/project/issues/12345/labels", createValidator(
        AddLabels.class,
        (a) ->
            a.getLabels().equals(labels)), getResource("labels_response_testdata.json"));
    assertThat(api.addLabels("example/project", 12345, labels)).hasSize(2);
  }

  @Test
  public void testAddAssignees() throws Exception {
    ImmutableList<String> assignees = ImmutableList.of("copybara", "octocat");
    trainMockPost(
        "/repos/example/project/issues/12345/assignees",
        createValidator(AddAssignees.class, request -> request.getAssignees().equals(assignees)),
        getResource("add_assignees_issue_response_testdata.json"));
    Issue issue = api.addAssignees("example/project", 12345, new AddAssignees(assignees));
    assertThat(issue.getAssignees().stream().map(assignee -> assignee.getLogin()))
        .containsExactlyElementsIn(assignees);
  }

  @Test
  public void testPostIssueComment() throws Exception {
    String comment = "This is a comment.";
    JsonValidator<CommentBody> validator = createValidator(CommentBody.class,
        (r) -> r.getBody().orElse("wrong body").equals(comment));
    trainMockPost("/repos/example/project/issues/12345/comments", validator,
        getResource("pulls_comment_12345_testdata.json"));
    api.postComment("example/project", 12345, comment);
    assertThat(validator.wasCalled()).isTrue();
  }

  @Test
  public void testGetInstallions() throws Exception {
    trainMockGet(
        "/orgs/octo-org/installations?per_page=100",
        getResource("get_installations_testdata.json"));
    ImmutableList<Installation> installations = api.getInstallations("octo-org");
    Installation installation = Iterables.getOnlyElement(installations);
    assertThat(installation.getAppSlug()).isEqualTo("github-actions");
    assertThat(installation.getTargetType()).isEqualTo("Organization");
    assertThat(installation.getRepositorySelection()).isEqualTo("selected");
  }

  @Test
  public void testGetOrganization() throws Exception {
    trainMockGet("/orgs/test-org", getResource("get_organization_testdata.json"));
    Organization organization = api.getOrganization("test-org");
    assertThat(organization.getTwoFactorRequirementEnabled()).isTrue();
  }

  @Test
  public void testListIssueComments() throws Exception {
    trainMockGet(
        "/repos/example/project/issues/12345/comments?per_page=100",
        getResource("list_issue_comments_testdata.json"));

    IssueComment comment =
        Iterables.getOnlyElement(api.listIssueComments("example/project", 12345));

    assertThat(comment.getBody()).isEqualTo("Me too");
    assertThat(comment.getId()).isEqualTo(1);
    assertThat(comment.getAuthorAssociation()).isEqualTo(AuthorAssociation.COLLABORATOR);
    assertThat(comment.getCreatedAt()).isEqualTo(ZonedDateTime.parse("2011-04-14T16:00:49Z"));
    assertThat(comment.getUpdatedAt()).isEqualTo(ZonedDateTime.parse("2011-04-14T16:00:49Z"));
    User user = comment.getUser();
    assertThat(user.getLogin()).isEqualTo("octocat");
    assertThat(user.getId()).isEqualTo(1);
    assertThat(user.getType()).isEqualTo("User");
  }

  @Test
  public void testGetIssuesOrPullRequestsSearchResults() throws Exception {
    String sha = "a06cc12413a862266a1bf1148436eb783e101bc9";
    String project = "google/copybara";
    trainMockGet(
        String.format("/search/issues?q=repo:%s+commit:%s+is:pr+state:closed", project, sha),
        getResource("search_issues_and_pull_requests_result_testdata.json"));
    IssuesAndPullRequestsSearchResults searchResults =
        api.getIssuesOrPullRequestsSearchResults(
            new GitHubApi.IssuesAndPullRequestsSearchRequestParams(
                project,
                sha,
                GitHubApi.IssuesAndPullRequestsSearchRequestParams.Type.PULL_REQUEST,
                GitHubApi.IssuesAndPullRequestsSearchRequestParams.State.CLOSED));
    IssuesAndPullRequestsSearchResult searchResult =
        Iterables.getOnlyElement(searchResults.getItems());
    assertThat(searchResult.getNumber()).isEqualTo(16);
  }

  @Test
  public void testCreateRelease() throws Exception {
    String project = "google/copybara";
    JsonValidator<CreateReleaseRequest> validator = createValidator(CreateReleaseRequest.class,
        (CreateReleaseRequest request) ->
            request.getName().equals("1.0.1")
            && request.getTagName().equals("v1.0.1")
            && request.getTargetCommitish().equals("abcdef01")
            && request.getBody().equals("This is the body"));
    trainMockPost(
        String.format("/repos/%s/releases", project),
        validator,
        getResource("create_release_testdata.json"));
    Release response = api.createRelease(project, new CreateReleaseRequest("v1.0.1")
        .withBody("This is the body")
        .withName("1.0.1")
        .withCommitish("abcdef01"));
    assertThat(response.getTarball())
        .isEqualTo("https://api.github.com/repos/octocat/Hello-World/tarball/v1.0.0");
    assertThat(response.getZip())
        .isEqualTo("https://api.github.com/repos/octocat/Hello-World/zipball/v1.0.0");
  }

  /**
   * We don't want CreatePullRequest to be instantiable, this subclass sidesteps the issue.
   **/
  public static class TestCreatePullRequest extends CreatePullRequest {
    public TestCreatePullRequest() {
      super("invalid", "invalid", "invalid", "invalid", false);
    }
  }

  /**
   * We don't want CreateIssueRequest to be instantiable, this subclass sidesteps the issue.
   **/
  public static class TestCreateIssueRequest extends CreateIssueRequest {
    public TestCreateIssueRequest() {
      super("invalid", "invalid", ImmutableList.of("foo", "bar"));
    }
  }

  /**
   * We don't want CreatePullRequest to be instantiable, this subclass sidesteps the issue.
   **/
  public static class TestCreateStatusRequest extends CreateStatusRequest {
    public TestCreateStatusRequest() {
      super(State.ERROR, "invalid", "invalid", "invalid");
    }
  }

  /**
   * We don't want CreatePullRequest to be instantiable, this subclass sidesteps the issue.
   **/
  public static class TestUpdateReferenceRequest extends UpdateReferenceRequest {
    public TestUpdateReferenceRequest() {
      super("6dcb09b5b57875f334f61aebed695e2e4193db5e", true);
    }
  }
}
