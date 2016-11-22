package org.rundeck.client.tool.commands;

import com.lexicalscope.jewel.cli.CommandLineInterface;
import com.lexicalscope.jewel.cli.Option;
import com.simplifyops.toolbelt.Command;
import com.simplifyops.toolbelt.CommandOutput;
import com.simplifyops.toolbelt.InputError;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import org.rundeck.client.api.RundeckApi;
import org.rundeck.client.api.model.*;
import org.rundeck.client.tool.options.*;
import org.rundeck.client.util.Client;
import org.rundeck.client.util.Format;
import org.rundeck.client.util.Util;
import retrofit2.Call;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Created by greg on 3/28/16.
 */
@Command(description = "List and manage Jobs.")
public class Jobs extends ApiCommand {

    public static final String UUID_REMOVE = "remove";
    public static final String UUID_PRESERVE = "preserve";

    public Jobs(final Supplier<Client<RundeckApi>> client) {
        super(client);
    }

    @CommandLineInterface(application = "purge") interface Purge extends JobPurgeOptions, ListOpts {
        @Option(longName = "confirm", shortName = "y", description = "Force confirmation of delete request.")
        boolean isConfirm();
    }

    @Command(description = "Delete jobs matching the query parameters. Optionally save the definitions to a file " +
                           "before deleting from the server. " +
                           "--idlist/-i, or --job/-j or --group/-g Options are required.")
    public boolean purge(Purge options, CommandOutput output) throws IOException, InputError {

        //if id,idlist specified, use directly
        //otherwise query for the list and assemble the ids

        List<String> ids = new ArrayList<>();
        if (options.isIdlist()) {
            ids = Arrays.asList(options.getIdlist().split("\\s*,\\s*"));
        } else {
            if (!options.isJob() && !options.isGroup()) {
                throw new InputError("must specify -i, or -j/-g to specify jobs to delete.");
            }
            Call<List<JobItem>> listCall;
            listCall = getClient().getService().listJobs(
                    options.getProject(),
                    options.getJob(),
                    options.getGroup()
            );
            List<JobItem> body = getClient().checkError(listCall);
            for (JobItem jobItem : body) {
                ids.add(jobItem.getId());
            }
        }

        if (options.isFile()) {
            list(options, output);
        }
        if (!options.isConfirm()) {
            //request confirmation
            String s = System.console().readLine("Really delete %d Jobs? (y/N) ", ids.size());

            if (!"y".equals(s)) {
                output.warning(String.format("Not deleting %d jobs", ids.size()));
                return false;
            }
        }

        DeleteJobsResult deletedJobs = getClient().checkError(getClient().getService().deleteJobs(ids));

        if (deletedJobs.isAllsuccessful()) {
            output.info(String.format("%d Jobs were deleted%n", deletedJobs.getRequestCount()));
            return true;
        }
        output.error(String.format("Failed to delete %d Jobs%n", deletedJobs.getFailed().size()));
        output.output(deletedJobs.getFailed().stream().map(DeleteJob::toBasicString).collect(Collectors.toList()));
        return false;
    }

    @CommandLineInterface(application = "load") interface Load extends JobLoadOptions, VerboseOption {
    }

    @Command(description = "Load Job definitions from a file in XML or YAML format.")
    public boolean load(Load options, CommandOutput output) throws IOException, InputError {
        if (!options.isFile()) {
            throw new InputError("-f is required");
        }
        File input = options.getFile();
        if (!input.canRead() || !input.isFile()) {
            throw new InputError(String.format("File is not readable or does not exist: %s", input));
        }

        RequestBody requestBody = RequestBody.create(
                "xml".equals(options.getFormat()) ? Client.MEDIA_TYPE_XML : Client.MEDIA_TYPE_YAML,
                input
        );

        Call<ImportResult> importResultCall = getClient().getService().loadJobs(
                options.getProject(),
                requestBody,
                options.getFormat(),
                options.getDuplicate(),
                options.isRemoveUuids() ? UUID_REMOVE : UUID_PRESERVE
        );
        ImportResult importResult = getClient().checkError(importResultCall);

        List<JobLoadItem> failed = importResult.getFailed();

        printLoadResult(importResult.getSucceeded(), "Succeeded", output, options.isVerbose());
        printLoadResult(importResult.getSkipped(), "Skipped", output, options.isVerbose());
        printLoadResult(failed, "Failed", output, options.isVerbose());

        return failed == null || failed.size() == 0;
    }

    private static void printLoadResult(
            final List<JobLoadItem> list,
            final String title,
            CommandOutput output, final boolean isVerbose
    )
    {
        if (null != list && list.size() > 0) {
            output.info(String.format("%d Jobs " + title + ":%n", list.size()));
            if (isVerbose) {
                output.output(list);
            } else {
                output.output(list.stream().map(JobLoadItem::toBasicString).collect(Collectors.toList()));
            }
        }
    }

    interface JobResultOptions extends JobOutputFormatOption, VerboseOption {

    }

    @CommandLineInterface(application = "list") interface ListOpts extends JobListOptions, JobResultOptions {
    }

    @Command(description = "List jobs found in a project, or download Job definitions (-f).")
    public void list(ListOpts options, CommandOutput output) throws IOException {
        if (options.isFile()) {
            //write response to file instead of parsing it
            Call<ResponseBody> responseCall;
            if (options.isIdlist()) {
                responseCall = getClient().getService().exportJobs(
                        options.getProject(),
                        options.getIdlist(),
                        options.getFormat()
                );
            } else {
                responseCall = getClient().getService().exportJobs(
                        options.getProject(),
                        options.getJob(),
                        options.getGroup(),
                        options.getFormat()
                );
            }
            ResponseBody body = getClient().checkError(responseCall);
            if ((!"yaml".equals(options.getFormat()) ||
                 !Client.hasAnyMediaType(body, Client.MEDIA_TYPE_YAML, Client.MEDIA_TYPE_TEXT_YAML)) &&
                !Client.hasAnyMediaType(body, Client.MEDIA_TYPE_XML, Client.MEDIA_TYPE_TEXT_XML)) {

                throw new IllegalStateException("Unexpected response format: " + body.contentType());
            }
            InputStream inputStream = body.byteStream();
            if ("-".equals(options.getFile().getName())) {
                long total = Util.copyStream(inputStream, System.out);
            } else {
                try (FileOutputStream out = new FileOutputStream(options.getFile())) {
                    long total = Util.copyStream(inputStream, out);
                    if (!options.isOutputFormat()) {
                        output.info(String.format(
                                "Wrote %d bytes of %s to file %s%n",
                                total,
                                body.contentType(),
                                options.getFile()
                        ));
                    }
                }
            }
        } else {
            Call<List<JobItem>> listCall;
            if (options.isIdlist()) {
                listCall = getClient().getService().listJobs(options.getProject(), options.getIdlist());
            } else {
                listCall = getClient().getService().listJobs(
                        options.getProject(),
                        options.getJob(),
                        options.getGroup()
                );
            }
            List<JobItem> body = getClient().checkError(listCall);
            if (!options.isOutputFormat()) {
                output.info(String.format("%d Jobs in project %s%n", body.size(), options.getProject()));
            }
            outputJobList(options, output, body);
        }
    }

    private void outputJobList(final JobResultOptions options, final CommandOutput output, final List<JobItem> body) {
        final Function<JobItem, ?> outformat;
        if (options.isVerbose()) {
            output.output(body.stream().map(JobItem::toMap).collect(Collectors.toList()));
            return;
        }
        if (options.isOutputFormat()) {
            outformat = Format.formatter(options.getOutputFormat(), JobItem::toMap, "%", "");
        } else {
            outformat = JobItem::toBasicString;
        }

        output.output(body.stream().map(outformat).collect(Collectors.toList()));
    }

    @CommandLineInterface(application = "info") interface InfoOpts extends JobResultOptions {

        @Option(shortName = "i", longName = "id", description = "Job ID")
        String getId();
    }

    @Command(description = "Get info about a Job by ID (API v18)")
    public void info(InfoOpts options, CommandOutput output) throws IOException {
        ScheduledJobItem body = getClient().checkError(getClient().getService().getJobInfo(options.getId()));
        outputJobList(options, output, Collections.singletonList(body));
    }

    /**
     * Split a job group/name into group then name parts
     *
     * @param job job group + name
     *
     * @return [job group (or null), name]
     */
    public static String[] splitJobNameParts(final String job) {
        if (!job.contains("/")) {
            return new String[]{null, job};
        }
        int i = job.lastIndexOf("/");
        String group = job.substring(0, i);
        String name = job.substring(i + 1);
        if ("".equals(group.trim())) {
            group = null;
        }
        return new String[]{group, name};

    }
}
