package se.bjurr.gitchangelog.internal.model;

import static com.google.common.base.Predicates.in;
import static com.google.common.collect.Iterables.filter;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.transform;
import static com.google.common.collect.Maps.newTreeMap;
import static com.google.common.collect.Multimaps.index;
import static java.util.TimeZone.getTimeZone;
import static java.util.regex.Pattern.compile;
import static se.bjurr.gitchangelog.internal.common.GitPredicates.ignoreCommits;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Multimap;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import se.bjurr.gitchangelog.api.model.*;
import se.bjurr.gitchangelog.internal.git.model.GitCommit;
import se.bjurr.gitchangelog.internal.git.model.GitTag;
import se.bjurr.gitchangelog.internal.settings.IssuesUtil;
import se.bjurr.gitchangelog.internal.settings.Settings;
import se.bjurr.gitchangelog.internal.settings.SettingsIssue;

public class Transformer {

  private final Settings settings;

  public Transformer(Settings settings) {
    this.settings = settings;
  }

  public List<Author> toAuthors(
      List<GitCommit> gitCommits, final HashMap<String, List<Changelog>> submoduleSections) {
    final Multimap<String, GitCommit> commitsPerAuthor =
        index(
            gitCommits,
            new Function<GitCommit, String>() {
              @Override
              public String apply(GitCommit input) {
                return input.getAuthorEmailAddress() + "-" + input.getAuthorName();
              }
            });

    Iterable<String> authorsWithCommits =
        filter(
            commitsPerAuthor.keySet(),
            new Predicate<String>() {
              @Override
              public boolean apply(String input) {
                return toCommits(commitsPerAuthor.get(input), submoduleSections).size() > 0;
              }
            });

    return newArrayList(
        transform(
            authorsWithCommits,
            new Function<String, Author>() {
              @Override
              public Author apply(String input) {
                List<GitCommit> gitCommitsOfSameAuthor = newArrayList(commitsPerAuthor.get(input));
                List<Commit> commitsOfSameAuthor =
                    toCommits(gitCommitsOfSameAuthor, submoduleSections);
                return new Author( //
                    commitsOfSameAuthor.get(0).getAuthorName(), //
                    commitsOfSameAuthor.get(0).getAuthorEmailAddress(), //
                    commitsOfSameAuthor);
              }
            }));
  }

  public List<Commit> toCommits(
      Collection<GitCommit> from, final HashMap<String, List<Changelog>> submoduleSections) {
    Iterable<GitCommit> filteredCommits = filter(from, ignoreCommits(settings));
    return newArrayList(
        transform(
            filteredCommits,
            new Function<GitCommit, Commit>() {
              @Override
              public Commit apply(GitCommit c) {
                return toCommit(
                    c,
                    submoduleSections == null
                        ? null
                        : submoduleSections.getOrDefault(c.getHash(), null));
              }
            }));
  }

  public List<Issue> toIssues(
      List<ParsedIssue> issues, final HashMap<String, List<Changelog>> submoduleSections) {
    Iterable<ParsedIssue> issuesWithCommits = filterWithCommits(issues, submoduleSections);

    return newArrayList(transform(issuesWithCommits, parsedIssueToIssue(submoduleSections)));
  }

  public List<IssueType> toIssueTypes(
      List<ParsedIssue> issues, final HashMap<String, List<Changelog>> submoduleSections) {
    Map<String, List<Issue>> issuesPerName = newTreeMap();

    for (ParsedIssue parsedIssue : filterWithCommits(issues, submoduleSections)) {
      if (!issuesPerName.containsKey(parsedIssue.getName())) {
        issuesPerName.put(parsedIssue.getName(), new ArrayList<Issue>());
      }
      Issue transformedIssues = parsedIssueToIssue(submoduleSections).apply(parsedIssue);
      issuesPerName
          .get(parsedIssue.getName()) //
          .add(transformedIssues);
    }

    List<IssueType> issueTypes = newArrayList();
    for (String name : issuesPerName.keySet()) {
      issueTypes.add(new IssueType(issuesPerName.get(name), name));
    }
    return issueTypes;
  }

  public List<Tag> toTags(
      List<GitTag> gitTags,
      final List<ParsedIssue> allParsedIssues,
      final HashMap<String, List<Changelog>> submoduleSections) {

    Iterable<Tag> tags =
        transform(
            gitTags,
            new Function<GitTag, Tag>() {
              @Override
              public Tag apply(GitTag input) {
                List<GitCommit> gitCommits = input.getGitCommits();
                final String readableName = toReadableTagName(input.getName());
                List<ParsedIssue> parsedIssues =
                    reduceParsedIssuesToOnlyGitCommits(allParsedIssues, gitCommits);
                List<Commit> commits = toCommits(gitCommits, submoduleSections);
                List<Author> authors = toAuthors(gitCommits, submoduleSections);
                List<Issue> issues = toIssues(parsedIssues, submoduleSections);
                List<IssueType> issueTypes = toIssueTypes(parsedIssues, submoduleSections);
                return new Tag(
                    readableName,
                    input.findAnnotation().orNull(),
                    commits,
                    authors,
                    issues,
                    issueTypes,
                    input.getTagTime() != null ? format(input.getTagTime()) : "",
                    input.getTagTime() != null ? input.getTagTime().getTime() : -1);
              }

              private List<ParsedIssue> reduceParsedIssuesToOnlyGitCommits(
                  final List<ParsedIssue> allParsedIssues, List<GitCommit> gitCommits) {
                List<ParsedIssue> parsedIssues = newArrayList();
                for (ParsedIssue candidate : allParsedIssues) {
                  List<GitCommit> candidateCommits =
                      newArrayList(filter(candidate.getGitCommits(), in(gitCommits)));
                  if (!candidateCommits.isEmpty()) {
                    ParsedIssue parsedIssue =
                        new ParsedIssue(
                            candidate.getSettingsIssueType(),
                            candidate.getName(),
                            candidate.getIssue(),
                            candidate.getDescription(),
                            candidate.getLink(),
                            candidate.getTitle().orNull(),
                            candidate.getIssueType(),
                            candidate.getLinkedIssues(),
                            candidate.getLabels());
                    parsedIssue.addCommits(candidateCommits);
                    parsedIssues.add(parsedIssue);
                  }
                }
                return parsedIssues;
              }
            });

    tags =
        filter(
            tags,
            new Predicate<Tag>() {
              @Override
              public boolean apply(Tag input) {
                return !input.getAuthors().isEmpty() && !input.getCommits().isEmpty();
              }
            });

    return newArrayList(tags);
  }

  private Iterable<ParsedIssue> filterWithCommits(
      List<ParsedIssue> issues, final HashMap<String, List<Changelog>> submoduleSections) {
    Iterable<ParsedIssue> issuesWithCommits =
        filter(
            issues,
            new Predicate<ParsedIssue>() {
              @Override
              public boolean apply(ParsedIssue input) {
                return !toCommits(input.getGitCommits(), submoduleSections).isEmpty();
              }
            });
    return issuesWithCommits;
  }

  private String format(Date commitTime) {
    SimpleDateFormat df = new SimpleDateFormat(this.settings.getDateFormat());
    df.setTimeZone(getTimeZone(this.settings.getTimeZone()));
    return df.format(commitTime);
  }

  private Function<ParsedIssue, Issue> parsedIssueToIssue(
      final HashMap<String, List<Changelog>> submoduleSections) {
    return new Function<ParsedIssue, Issue>() {
      @Override
      public Issue apply(ParsedIssue input) {
        List<GitCommit> gitCommits = input.getGitCommits();
        return new Issue( //
            toCommits(gitCommits, submoduleSections), //
            toAuthors(gitCommits, submoduleSections), //
            input.getName(), //
            input.getTitle().or(""), //
            input.getIssue(), //
            input.getSettingsIssueType(), //
            input.getDescription(),
            input.getLink(), //
            input.getIssueType(), //
            input.getLinkedIssues(), //
            input.getLabels());
      }
    };
  }

  private String removeIssuesFromString(
      boolean removeIssueFromMessage, List<SettingsIssue> issues, String string) {
    if (removeIssueFromMessage) {
      for (SettingsIssue issue : issues) {
        string = string.replaceAll(issue.getPattern(), "");
      }
    }
    return string;
  }

  private Commit toCommit(GitCommit gitCommit, List<Changelog> submoduleSections) {
    return new Commit( //
        gitCommit.getAuthorName(), //
        gitCommit.getAuthorEmailAddress(), //
        format(gitCommit.getCommitTime()), //
        gitCommit.getCommitTime().getTime(), //
        toMessage(
            this.settings.removeIssueFromMessage(),
            new IssuesUtil(this.settings).getIssues(),
            gitCommit.getMessage()), //
        gitCommit.getHash(), //
        gitCommit.isMerge(),
        submoduleSections);
  }

  private String toReadableTagName(String input) {
    Matcher matcher = compile(this.settings.getReadableTagName()).matcher(input);
    if (matcher.find()) {
      if (matcher.groupCount() == 0) {
        throw new RuntimeException(
            "Pattern: \""
                + this.settings.getReadableTagName()
                + "\" did not match any group in: \""
                + input
                + "\"");
      }
      return matcher.group(1);
    }
    return input;
  }

  @VisibleForTesting
  String toMessage(boolean removeIssueFromMessage, List<SettingsIssue> issues, String message) {
    return removeIssuesFromString(removeIssueFromMessage, issues, message);
  }
}
