package reposense;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sourceforge.argparse4j.helper.HelpScreenException;
import reposense.git.GitConfig;
import reposense.git.GitVersion;
import reposense.model.CliArguments;
import reposense.model.RepoConfiguration;
import reposense.model.ReportConfiguration;
import reposense.model.RunConfigurationDecider;
import reposense.parser.ArgsParser;
import reposense.parser.InvalidCsvException;
import reposense.parser.InvalidHeaderException;
import reposense.parser.ParseException;
import reposense.report.ReportGenerator;
import reposense.system.LogsManager;
import reposense.system.ReportServer;
import reposense.util.FileUtil;
import reposense.util.TimeUtil;

/**
 * The main RepoSense class.
 */
public class RepoSense {
    private static final Logger logger = LogsManager.getLogger(RepoSense.class);
    private static final int SERVER_PORT_NUMBER = 9000;
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("E MMM d HH:mm:ss yyyy z");
    private static final String VERSION_UNSPECIFIED = "unspecified";
    private static final String FINDING_PREVIOUS_AUTHORS_INVALID_VERSION_WARNING_MESSAGE =
            "--find-previous-authors/-F requires git version 2.23 and above. Feature will be disabled for this run";

    /**
     * The entry point of the program.
     * Additional flags are provided by the user in {@code args}.
     */
    public static void main(String[] args) {
        try {
            TimeUtil.startTimer();
            CliArguments cliArguments = ArgsParser.parse(args);
            List<RepoConfiguration> configs = null;
            ReportConfiguration reportConfig = new ReportConfiguration();

            if (cliArguments.isViewModeOnly()) {
                ReportServer.startServer(SERVER_PORT_NUMBER, cliArguments.getReportDirectoryPath().toAbsolutePath());
                return;
            }

            configs = RunConfigurationDecider.getRunConfiguration(cliArguments).getRepoConfigurations();
            reportConfig = cliArguments.getReportConfiguration();

            RepoConfiguration.setFormatsToRepoConfigs(configs, cliArguments.getFormats());
            RepoConfiguration.setDatesToRepoConfigs(configs, cliArguments.getSinceDate(), cliArguments.getUntilDate());
            RepoConfiguration.setZoneIdToRepoConfigs(configs, cliArguments.getZoneId());
            RepoConfiguration.setStandaloneConfigIgnoredToRepoConfigs(configs,
                    cliArguments.isStandaloneConfigIgnored());
            RepoConfiguration.setFileSizeLimitIgnoredToRepoConfigs(configs,
                    cliArguments.isFileSizeLimitIgnored());
            RepoConfiguration.setIsLastModifiedDateIncludedToRepoConfigs(configs,
                    cliArguments.isLastModifiedDateIncluded());
            RepoConfiguration.setIsShallowCloningPerformedToRepoConfigs(configs,
                    cliArguments.isShallowCloningPerformed());
            RepoConfiguration.setIsFindingPreviousAuthorsPerformedToRepoConfigs(configs,
                    cliArguments.isFindingPreviousAuthorsPerformed());

            if (RepoConfiguration.isAnyRepoFindingPreviousAuthors(configs)
                    && !GitVersion.isGitVersionSufficientForFindingPreviousAuthors()) {
                logger.warning(FINDING_PREVIOUS_AUTHORS_INVALID_VERSION_WARNING_MESSAGE);
                RepoConfiguration.setToFalseIsFindingPreviousAuthorsPerformedToRepoConfigs(configs);
            }

            List<String[]> globalGitConfig = GitConfig.getGlobalGitLfsConfig();
            if (globalGitConfig.size() != 0) {
                GitConfig.setGlobalGitLfsConfig(GitConfig.SKIP_SMUDGE_CONFIG_SETTINGS);
            }

            ReportGenerator reportGenerator = new ReportGenerator();
            List<Path> reportFoldersAndFiles = reportGenerator.generateReposReport(configs,
                    cliArguments.getOutputFilePath().toAbsolutePath().toString(),
                    cliArguments.getAssetsFilePath().toAbsolutePath().toString(), reportConfig,
                    formatter.format(ZonedDateTime.now(cliArguments.getZoneId())),
                    cliArguments.getSinceDate(), cliArguments.getUntilDate(),
                    cliArguments.isSinceDateProvided(), cliArguments.isUntilDateProvided(),
                    cliArguments.getNumCloningThreads(), cliArguments.getNumAnalysisThreads(),
                    TimeUtil::getElapsedTime, cliArguments.getZoneId(), cliArguments.isFreshClonePerformed());

            FileUtil.zipFoldersAndFiles(reportFoldersAndFiles, cliArguments.getOutputFilePath().toAbsolutePath(),
                    ".json");

            // Set back to user's initial global git lfs config
            GitConfig.setGlobalGitLfsConfig(globalGitConfig);

            logger.info(TimeUtil.getElapsedTimeMessage());

            if (cliArguments.isAutomaticallyLaunching()) {
                ReportServer.startServer(SERVER_PORT_NUMBER, cliArguments.getOutputFilePath().toAbsolutePath());
            }
        } catch (IOException | ParseException | InvalidCsvException | InvalidHeaderException e) {
            logger.log(Level.WARNING, e.getMessage(), e);
        } catch (HelpScreenException e) {
            // help message was printed by the ArgumentParser; it is safe to exit.
        }

        LogsManager.moveLogFileToOutputFolder();
    }

    public static String getVersion() {
        String version = RepoSense.class.getPackage().getImplementationVersion();

        if (version == null || version.equals(VERSION_UNSPECIFIED)) {
            version = System.getProperty("version");
        }

        return version;
    }
}
