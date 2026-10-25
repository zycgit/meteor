#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <ftw.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* Java writes a counted NUL-delimited launch. This program never interprets JSON. */
typedef struct {
    char *storage;
    char **values;
    size_t count;
} tokens;

typedef struct {
    char *id, *run, *group, *cwd, *temporary;
    char **arguments, **environment, **unset;
    int argument_count, environment_count, unset_count;
    tokens input;
} launch;

typedef struct {
    pid_t pid, parent;
    unsigned long long ticks;
    char state;
    bool owned;
} identity;

typedef struct {
    int source, target, spool;
    off_t received, delivered;
    bool ended, target_open;
    char name[32];
} output;

static volatile sig_atomic_t requested_stop;
static volatile sig_atomic_t requested_force;
static char evidence_directory[PATH_MAX];
static char failure[2048], cleanup_failure[2048];
static launch configuration;
static pid_t wrapper_pid, child_pid;
static unsigned long long wrapper_ticks, child_ticks;
static bool tree_empty, temporary_removed;
static int raw_exit = -SIGTERM;
static char started[64], finished[64];

static double monotonic(void) {
    struct timespec value;
    clock_gettime(CLOCK_MONOTONIC, &value);
    return (double)value.tv_sec + (double)value.tv_nsec / 1000000000.0;
}

static void timestamp(char *value, size_t capacity) {
    struct timespec now;
    struct tm utc;
    clock_gettime(CLOCK_REALTIME, &now);
    gmtime_r(&now.tv_sec, &utc);
    strftime(value, capacity, "%Y-%m-%dT%H:%M:%SZ", &utc);
}

static void set_error(char *destination, size_t capacity, const char *message) {
    if (!destination[0]) snprintf(destination, capacity, "%s", message);
}

static void system_error(char *destination, size_t capacity, const char *operation) {
    char message[2048];
    snprintf(message, sizeof(message), "%s: %s", operation, strerror(errno));
    set_error(destination, capacity, message);
}

static int path_for(char *path, const char *name) {
    int length = snprintf(path, PATH_MAX, "%s/%s", evidence_directory, name);
    if (length < 0 || length >= PATH_MAX) { errno = ENAMETOOLONG; return -1; }
    return 0;
}

static int write_all(int fd, const void *buffer, size_t length) {
    const char *cursor = buffer;
    while (length) {
        ssize_t count = write(fd, cursor, length);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        cursor += count;
        length -= (size_t)count;
    }
    return 0;
}

static int write_token(int fd, const char *text) {
    return write_all(fd, text, strlen(text) + 1);
}

static void free_tokens(tokens *data) {
    free(data->values);
    free(data->storage);
    memset(data, 0, sizeof(*data));
}

static int read_tokens(const char *path, tokens *data, size_t limit) {
    memset(data, 0, sizeof(*data));
    int descriptor = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) return -1;
    struct stat information;
    if (fstat(descriptor, &information) || !S_ISREG(information.st_mode)
            || information.st_size <= 0 || (size_t)information.st_size > limit) {
        close(descriptor);
        errno = EINVAL;
        return -1;
    }
    size_t length = (size_t)information.st_size;
    data->storage = malloc(length);
    if (!data->storage) { close(descriptor); return -1; }
    size_t position = 0;
    while (position < length) {
        ssize_t received = read(descriptor, data->storage + position, length - position);
        if (received < 0 && errno == EINTR) continue;
        if (received <= 0) { close(descriptor); free_tokens(data); errno = EINVAL; return -1; }
        position += (size_t)received;
    }
    close(descriptor);
    if (data->storage[length - 1] != 0) { free_tokens(data); errno = EINVAL; return -1; }
    for (size_t index = 0; index < length; index++) if (!data->storage[index]) data->count++;
    data->values = calloc(data->count + 1, sizeof(char *));
    if (!data->values) { free_tokens(data); return -1; }
    position = 0;
    for (size_t index = 0; index < data->count; index++) {
        data->values[index] = data->storage + position;
        position += strlen(data->values[index]) + 1;
    }
    return 0;
}

static int parse_count(tokens *data, size_t *cursor) {
    if (*cursor >= data->count) return -1;
    char *end;
    long count = strtol(data->values[(*cursor)++], &end, 10);
    if (*end || count < 0 || count > 65536 || (size_t)count > data->count - *cursor) return -1;
    return (int)count;
}

static int load_launch(void) {
    char path[PATH_MAX];
    if (path_for(path, "launch.native") || read_tokens(path, &configuration.input, 16 * 1024 * 1024)) return -1;
    tokens *data = &configuration.input;
    if (data->count < 10 || strcmp(data->values[0], "meteor-peer-v1")) { errno = EINVAL; return -1; }
    configuration.id = data->values[1];
    configuration.run = data->values[2];
    configuration.group = data->values[3];
    configuration.cwd = data->values[4];
    configuration.temporary = data->values[5];
    if (!configuration.id[0] || configuration.cwd[0] != '/' || configuration.temporary[0] != '/') {
        errno = EINVAL; return -1;
    }
    size_t cursor = 6;
    configuration.argument_count = parse_count(data, &cursor);
    if (configuration.argument_count <= 0) { errno = EINVAL; return -1; }
    configuration.arguments = calloc((size_t)configuration.argument_count + 1, sizeof(char *));
    if (!configuration.arguments) return -1;
    for (int index = 0; index < configuration.argument_count; index++) {
        configuration.arguments[index] = data->values[cursor++];
    }
    configuration.environment_count = parse_count(data, &cursor);
    if (configuration.environment_count < 0) { errno = EINVAL; return -1; }
    configuration.environment = data->values + cursor;
    cursor += (size_t)configuration.environment_count;
    configuration.unset_count = parse_count(data, &cursor);
    if (configuration.unset_count < 0) { errno = EINVAL; return -1; }
    configuration.unset = data->values + cursor;
    cursor += (size_t)configuration.unset_count;
    if (cursor != data->count) { errno = EINVAL; return -1; }
    return 0;
}

/* Parse only the kernel's PID, parent, state and immutable start tick. */
static bool process_identity(pid_t pid, identity *result) {
    char path[64], content[8192];
    snprintf(path, sizeof(path), "/proc/%ld/stat", (long)pid);
    int descriptor = open(path, O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) return false;
    ssize_t length = read(descriptor, content, sizeof(content) - 1);
    close(descriptor);
    if (length <= 0) return false;
    content[length] = 0;
    char *end_name = strrchr(content, ')');
    if (!end_name) return false;
    memset(result, 0, sizeof(*result));
    result->pid = pid;
    char *save, *field = strtok_r(end_name + 2, " ", &save);
    for (int number = 3; field; number++, field = strtok_r(NULL, " ", &save)) {
        if (number == 3) result->state = field[0];
        if (number == 4) result->parent = (pid_t)strtol(field, NULL, 10);
        if (number == 22) {
            result->ticks = strtoull(field, NULL, 10);
            return true;
        }
    }
    return false;
}

static int signal_identity(pid_t pid, unsigned long long ticks, int number) {
    int descriptor = (int)syscall(SYS_pidfd_open, pid, 0);
    if (descriptor < 0) return errno == ESRCH ? 0 : -1;
    identity current;
    int result = 0;
    if (process_identity(pid, &current) && current.ticks == ticks) {
        result = (int)syscall(SYS_pidfd_send_signal, descriptor, number, NULL, 0);
        if (result < 0 && errno == ESRCH) result = 0;
    }
    close(descriptor);
    return result;
}

static identity *descendants(size_t *count) {
    *count = 0;
    DIR *directory = opendir("/proc");
    if (!directory) return NULL;
    size_t capacity = 128;
    identity *processes = calloc(capacity, sizeof(identity));
    struct dirent *entry;
    while (processes && (entry = readdir(directory))) {
        char *end;
        long pid = strtol(entry->d_name, &end, 10);
        if (*end || pid <= 0) continue;
        if (*count == capacity) {
            capacity *= 2;
            identity *larger = realloc(processes, capacity * sizeof(identity));
            if (!larger) { free(processes); processes = NULL; break; }
            processes = larger;
        }
        if (process_identity((pid_t)pid, &processes[*count])) (*count)++;
    }
    closedir(directory);
    if (!processes) { *count = 0; return NULL; }
    // Subreaper adoption retains ownership when a child creates a new session or double-forks.
    bool changed;
    do {
        changed = false;
        for (size_t index = 0; index < *count; index++) {
            identity *candidate = &processes[index];
            if (candidate->owned || candidate->pid == wrapper_pid) continue;
            bool parent_owned = candidate->parent == wrapper_pid;
            for (size_t parent = 0; !parent_owned && parent < *count; parent++) {
                parent_owned = processes[parent].owned && candidate->parent == processes[parent].pid;
            }
            if (parent_owned) { candidate->owned = true; changed = true; }
        }
    } while (changed);
    return processes;
}

static int publish(const char *state, const char *remaining) {
    char path[PATH_MAX], temporary[PATH_MAX];
    if (path_for(path, "status.native")) return -1;
    int length = snprintf(temporary, sizeof(temporary), "%s.%ld.tmp", path, (long)getpid());
    if (length < 0 || (size_t)length >= sizeof(temporary)) { errno = ENAMETOOLONG; return -1; }
    int descriptor = open(temporary, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (descriptor < 0) return -1;
    char wrapper[32], wrapper_start[32], child[32] = "", child_start[32] = "", code[32] = "", raw[32] = "";
    snprintf(wrapper, sizeof(wrapper), "%ld", (long)wrapper_pid);
    snprintf(wrapper_start, sizeof(wrapper_start), "%llu", wrapper_ticks);
    if (child_pid) {
        snprintf(child, sizeof(child), "%ld", (long)child_pid);
        snprintf(child_start, sizeof(child_start), "%llu", child_ticks);
    }
    if (finished[0]) {
        snprintf(raw, sizeof(raw), "%d", raw_exit);
        snprintf(code, sizeof(code), "%d", raw_exit < 0 ? 128 - raw_exit : raw_exit);
    }
    const char *fields[] = {
        "meteor-status-v1", "processId", configuration.id, "runId", configuration.run,
        "groupId", configuration.group, "state", state, "wrapperPid", wrapper,
        "wrapperStartTicks", wrapper_start, "pid", child, "pgid", child, "pidStartTicks", child_start,
        "exitCode", code, "rawExitCode", raw, "processGroupEmpty", tree_empty ? "1" : "0",
        "remainingPids", remaining, "temporaryDirectoryRemoved", temporary_removed ? "1" : "0",
        "startedAt", started, "finishedAt", finished, "error", failure, "cleanupFailure", cleanup_failure
    };
    int result = 0;
    for (size_t index = 0; index < sizeof(fields) / sizeof(fields[0]); index++) {
        if (write_token(descriptor, fields[index])) { result = -1; break; }
    }
    if (fsync(descriptor)) result = -1;
    if (close(descriptor)) result = -1;
    if (!result && rename(temporary, path)) result = -1;
    if (result) unlink(temporary);
    return result;
}

static const char *status_field(tokens *data, const char *key) {
    if (!data->count || strcmp(data->values[0], "meteor-status-v1") || data->count % 2 != 1) return NULL;
    for (size_t index = 1; index + 1 < data->count; index += 2) {
        if (!strcmp(data->values[index], key)) return data->values[index + 1];
    }
    return NULL;
}

static bool cancellation(void) {
    char path[PATH_MAX];
    if (path_for(path, "cancel.control")) { requested_stop = requested_force = 1; return true; }
    tokens control;
    if (read_tokens(path, &control, 65536)) {
        if (errno == ENOENT) return requested_stop;
        system_error(failure, sizeof(failure), "Invalid cancellation file");
        requested_stop = requested_force = 1;
        return true;
    }
    bool valid = control.count == 4 && !strcmp(control.values[0], "meteor-cancel-v1");
    if (!valid) set_error(failure, sizeof(failure), "Invalid cancellation file: missing processId or invalid control format");
    else if (strcmp(control.values[1], configuration.id)) {
        valid = false;
        set_error(failure, sizeof(failure), "Cancellation processId does not match launch");
    }
    if (valid) {
        requested_force |= !strcmp(control.values[2], "1");
        if (control.values[3][0]) {
            set_error(failure, sizeof(failure), control.values[3]);
            requested_force = 1;
        }
    } else requested_force = 1;
    requested_stop = 1;
    free_tokens(&control);
    return true;
}

static void request_signal(int number) {
    (void)number;
    requested_stop = 1;
}

static void nonblocking(int descriptor) {
    int flags = fcntl(descriptor, F_GETFL);
    if (flags >= 0) fcntl(descriptor, F_SETFL, flags | O_NONBLOCK);
}

/* A spool separates complete evidence from a slow or disconnected docker exec pipe. */
static void pump_output(output *stream) {
    char buffer[65536];
    if (!stream->ended) {
        for (int batch = 0; batch < 8; batch++) {
            ssize_t received = read(stream->source, buffer, sizeof(buffer));
            if (received > 0) {
                if (pwrite(stream->spool, buffer, (size_t)received, stream->received) != received) {
                    system_error(cleanup_failure, sizeof(cleanup_failure), "Cannot persist peer output");
                    stream->ended = true;
                    close(stream->source);
                    stream->source = -1;
                    requested_stop = requested_force = 1;
                    break;
                }
                stream->received += received;
            } else if (!received) { stream->ended = true; break; }
            else if (errno == EINTR) continue;
            else if (errno != EAGAIN && errno != EWOULDBLOCK) {
                system_error(cleanup_failure, sizeof(cleanup_failure), "Cannot read peer output");
                stream->ended = true;
                close(stream->source);
                stream->source = -1;
                requested_stop = requested_force = 1;
                break;
            } else break;
        }
    }
    if (stream->target_open && stream->delivered < stream->received) {
        size_t count = (size_t)(stream->received - stream->delivered);
        if (count > sizeof(buffer)) count = sizeof(buffer);
        ssize_t received = pread(stream->spool, buffer, count, stream->delivered);
        if (received > 0) {
            ssize_t written = write(stream->target, buffer, (size_t)received);
            if (written > 0) stream->delivered += written;
            else if (written < 0 && errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
                stream->target_open = false;
            }
        }
    }
}

static void pump_input(int *destination) {
    static char pending[65536];
    static size_t used, delivered;
    static bool ended;
    if (*destination < 0) return;
    if (!ended && used == delivered) {
        ssize_t received = read(STDIN_FILENO, pending, sizeof(pending));
        if (received > 0) { used = (size_t)received; delivered = 0; }
        else if (!received || (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK)) ended = true;
    }
    if (used > delivered) {
        ssize_t written = write(*destination, pending + delivered, used - delivered);
        if (written > 0) delivered += (size_t)written;
        else if (written < 0 && errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK) {
            close(*destination); *destination = -1; return;
        }
    }
    if (ended && used == delivered) { close(*destination); *destination = -1; }
}

static int remove_entry(const char *path, const struct stat *information, int type, struct FTW *walk) {
    (void)information; (void)type; (void)walk;
    return remove(path);
}

static int make_directory(const char *path) {
    char copy[PATH_MAX];
    if (strlen(path) >= sizeof(copy)) { errno = ENAMETOOLONG; return -1; }
    strcpy(copy, path);
    for (char *cursor = copy + 1; *cursor; cursor++) {
        if (*cursor != '/') continue;
        *cursor = 0;
        if (mkdir(copy, 0700) && errno != EEXIST) return -1;
        *cursor = '/';
    }
    return mkdir(copy, 0700);
}

static int private_environment(void) {
    for (int index = 0; index < configuration.unset_count; index++) {
        if (unsetenv(configuration.unset[index])) return -1;
    }
    for (int index = 0; index < configuration.environment_count; index++) {
        char *assignment = configuration.environment[index];
        char *equal = strchr(assignment, '=');
        if (!equal || equal == assignment) { errno = EINVAL; return -1; }
        *equal = 0;
        int result = setenv(assignment, equal + 1, 1);
        *equal = '=';
        if (result) return -1;
    }
    for (size_t index = 0; index < 4; index++) {
        const char *names[] = {"TMPDIR", "TMP", "TEMP", "METEOR_TEST_JOB_DIRECTORY"};
        if (setenv(names[index], configuration.temporary, 1)) return -1;
    }
    const char *suffixes[] = {"/home", "/home/.cache", "/home/.config", "/home/.local/share"};
    const char *variables[] = {"HOME", "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME"};
    char home[PATH_MAX];
    for (size_t index = 0; index < 4; index++) {
        char path[PATH_MAX];
        int length = snprintf(path, sizeof(path), "%s%s", configuration.temporary, suffixes[index]);
        if (length < 0 || (size_t)length >= sizeof(path)) { errno = ENAMETOOLONG; return -1; }
        if (make_directory(path) && errno != EEXIST) return -1;
        if (setenv(variables[index], path, 1)) return -1;
        if (!index) strcpy(home, path);
    }
    const char *existing = getenv("JDK_JAVA_OPTIONS");
    char *options;
    if (asprintf(&options, "%s%s\"-Djava.io.tmpdir=%s\" \"-Duser.home=%s\"",
            existing ? existing : "", existing && existing[0] ? " " : "", configuration.temporary, home) < 0) return -1;
    int result = setenv("JDK_JAVA_OPTIONS", options, 1);
    free(options);
    return result;
}

static bool reap_children(bool *root_exited) {
    bool children_exist = false;
    for (;;) {
        int status;
        pid_t result = waitpid(-1, &status, WNOHANG);
        if (result > 0) {
            if (result == child_pid) {
                *root_exited = true;
                raw_exit = WIFSIGNALED(status) ? -WTERMSIG(status) : WEXITSTATUS(status);
            }
        } else if (result == 0) { children_exist = true; break; }
        else if (errno == EINTR) continue;
        else break;
    }
    return children_exist;
}

static int run_peer(void) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = request_signal;
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGUSR1, &action, NULL);
    signal(SIGPIPE, SIG_IGN);
    wrapper_pid = getpid();
    identity self;
    if (!process_identity(wrapper_pid, &self) || prctl(PR_SET_CHILD_SUBREAPER, 1)) {
        perror("Cannot enable peer child subreaper"); return 1;
    }
    wrapper_ticks = self.ticks;
    timestamp(started, sizeof(started));
    if (publish("starting", "")) { perror("Cannot publish peer identity"); return 1; }

    output streams[2];
    memset(streams, 0, sizeof(streams));
    for (int index = 0; index < 2; index++) {
        output *stream = &streams[index];
        stream->source = -1;
        stream->target = index + 1;
        stream->target_open = true;
        snprintf(stream->name, sizeof(stream->name), "%s.log", index ? "stderr" : "stdout");
        char path[PATH_MAX];
        path_for(path, stream->name);
        stream->spool = open(path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        if (stream->spool < 0) system_error(failure, sizeof(failure), "Cannot create output evidence");
    }
    bool temporary_created = false, root_exited = false;
    int input = -1;
    if (!failure[0]) {
        if (make_directory(configuration.temporary)) system_error(failure, sizeof(failure), "Cannot create private temporary directory");
        else temporary_created = true;
    }
    if (!failure[0] && !cancellation()) {
        int pipes[3][2];
        bool pipes_ready = true;
        for (int index = 0; index < 3; index++) {
            if (pipe2(pipes[index], O_CLOEXEC)) { pipes_ready = false; break; }
        }
        if (!pipes_ready) system_error(failure, sizeof(failure), "Cannot create peer pipes");
        else {
            child_pid = fork();
            if (child_pid == 0) {
                // The business process gets its own session and no executor signal handlers.
                signal(SIGTERM, SIG_DFL); signal(SIGINT, SIG_DFL);
                signal(SIGUSR1, SIG_DFL); signal(SIGPIPE, SIG_DFL);
                if (setsid() < 0 || dup2(pipes[0][0], STDIN_FILENO) < 0
                        || dup2(pipes[1][1], STDOUT_FILENO) < 0 || dup2(pipes[2][1], STDERR_FILENO) < 0) {
                    perror("Cannot configure peer session"); _exit(126);
                }
                for (int index = 0; index < 3; index++) { close(pipes[index][0]); close(pipes[index][1]); }
                if (chdir(configuration.cwd) || private_environment()) { perror("Cannot configure peer process"); _exit(126); }
                execvp(configuration.arguments[0], configuration.arguments);
                perror("Cannot execute peer command");
                _exit(127);
            }
            if (child_pid < 0) { child_pid = 0; system_error(failure, sizeof(failure), "Cannot fork peer process"); }
            close(pipes[0][0]); close(pipes[1][1]); close(pipes[2][1]);
            input = pipes[0][1];
            streams[0].source = pipes[1][0]; streams[1].source = pipes[2][0];
            if (child_pid) {
                identity child;
                if (process_identity(child_pid, &child)) child_ticks = child.ticks;
                if (publish("running", "")) system_error(failure, sizeof(failure), "Cannot publish running peer");
            }
        }
    }
    nonblocking(STDIN_FILENO); nonblocking(STDOUT_FILENO); nonblocking(STDERR_FILENO);
    if (input >= 0) nonblocking(input);
    for (int index = 0; index < 2; index++) {
        if (streams[index].source < 0) streams[index].ended = true;
        else nonblocking(streams[index].source);
    }

    // Cleanup is shared by normal exit, control files, wrapper signals and setup errors.
    bool cleaning = failure[0] || !child_pid;
    double grace_end = monotonic() + (requested_force ? 0 : 3);
    double cleanup_end = grace_end + 10;
    double output_end = 0;
    char remaining[8192] = "";
    while (child_pid) {
        bool children_exist = reap_children(&root_exited);
        if (cancellation() || root_exited || failure[0]) {
            if (!cleaning) {
                cleaning = true;
                grace_end = monotonic() + (requested_force || failure[0] ? 0 : 3);
                cleanup_end = grace_end + 10;
            }
        }
        pump_input(&input);
        for (int index = 0; index < 2; index++) pump_output(&streams[index]);
        if (cleaning) {
            size_t count;
            identity *processes = descendants(&count);
            if (!processes) { system_error(cleanup_failure, sizeof(cleanup_failure), "Cannot enumerate peer descendants"); break; }
            size_t alive = 0, offset = 0;
            remaining[0] = 0;
            for (size_t index = 0; index < count; index++) {
                identity *process = &processes[index];
                if (!process->owned || process->state == 'Z') continue;
                alive++;
                int length = snprintf(remaining + offset, sizeof(remaining) - offset, "%s%ld", offset ? "," : "", (long)process->pid);
                if (length > 0 && (size_t)length < sizeof(remaining) - offset) offset += (size_t)length;
                int number = requested_force || failure[0] || monotonic() >= grace_end ? SIGKILL : SIGTERM;
                if (signal_identity(process->pid, process->ticks, number)) {
                    system_error(cleanup_failure, sizeof(cleanup_failure), "Cannot signal owned peer identity");
                }
            }
            free(processes);
            if (!alive && !children_exist) { tree_empty = true; break; }
            if (monotonic() >= cleanup_end) {
                set_error(cleanup_failure, sizeof(cleanup_failure), "Peer descendants did not exit"); break;
            }
        }
        struct timespec pause = {.tv_sec = 0, .tv_nsec = 2000000};
        nanosleep(&pause, NULL);
    }
    if (!child_pid) { tree_empty = true; if (failure[0]) raw_exit = 1; }
    if (input >= 0) close(input);

    // Always finish evidence; inherited output has a bounded five-second drain budget.
    output_end = monotonic() + 5;
    for (;;) {
        bool complete = true;
        for (int index = 0; index < 2; index++) {
            output *stream = &streams[index];
            pump_output(stream);
            complete &= stream->ended && (!stream->target_open || stream->delivered == stream->received);
        }
        if (complete) break;
        if (monotonic() >= output_end) {
            for (int index = 0; index < 2; index++) {
                output *stream = &streams[index];
                if (!stream->ended) set_error(cleanup_failure, sizeof(cleanup_failure), "Peer output did not reach EOF");
                else if (stream->target_open && stream->delivered < stream->received) {
                    set_error(cleanup_failure, sizeof(cleanup_failure), "inherited output did not drain");
                }
            }
            break;
        }
        struct timespec pause = {.tv_sec = 0, .tv_nsec = 2000000};
        nanosleep(&pause, NULL);
    }
    for (int index = 0; index < 2; index++) {
        if (streams[index].source >= 0) close(streams[index].source);
        if (streams[index].spool >= 0) {
            if (fsync(streams[index].spool)) {
                system_error(cleanup_failure, sizeof(cleanup_failure), "Cannot sync peer output evidence");
            }
            if (close(streams[index].spool)) {
                system_error(cleanup_failure, sizeof(cleanup_failure), "Cannot close peer output evidence");
            }
        }
    }
    if (temporary_created && nftw(configuration.temporary, remove_entry, 32, FTW_DEPTH | FTW_PHYS)) {
        system_error(cleanup_failure, sizeof(cleanup_failure), "Cannot remove private temporary directory");
    }
    struct stat temporary;
    temporary_removed = lstat(configuration.temporary, &temporary) && errno == ENOENT;
    timestamp(finished, sizeof(finished));
    bool failed = failure[0] || cleanup_failure[0];
    if (publish(failed ? "failed" : "exited", remaining)) { perror("Cannot publish terminal peer state"); return 1; }
    int code = raw_exit < 0 ? 128 - raw_exit : raw_exit;
    return code || !failed ? code : 1;
}

static int stop_peer(bool force) {
    char lock_path[PATH_MAX], request_path[PATH_MAX], control_path[PATH_MAX], state_path[PATH_MAX];
    path_for(lock_path, "cancel.lock"); path_for(request_path, "cancel.request");
    path_for(control_path, "cancel.control"); path_for(state_path, "status.native");
    int lock = open(lock_path, O_CREAT | O_WRONLY | O_CLOEXEC, 0600);
    if (lock < 0 || flock(lock, LOCK_EX)) { perror("Cannot lock peer cancellation"); return 1; }
    char previous_error[2048] = "";
    tokens control;
    const char *sources[] = {control_path, request_path};
    for (size_t index = 0; index < 2; index++) {
        if (read_tokens(sources[index], &control, 65536)) {
            if (errno != ENOENT) { system_error(previous_error, sizeof(previous_error), "Invalid cancellation file"); force = true; }
            continue;
        }
        if (control.count != 4 || strcmp(control.values[0], "meteor-cancel-v1")) {
            set_error(previous_error, sizeof(previous_error), "Invalid cancellation file: missing processId or invalid control format");
            force = true;
        } else if (strcmp(control.values[1], configuration.id)) {
            set_error(previous_error, sizeof(previous_error), "Cancellation processId does not match launch");
            force = true;
        } else {
            force |= !strcmp(control.values[2], "1");
            if (control.values[3][0]) { set_error(previous_error, sizeof(previous_error), control.values[3]); force = true; }
        }
        free_tokens(&control);
    }
    char temporary[PATH_MAX];
    int length = snprintf(temporary, sizeof(temporary), "%s.%ld.tmp", control_path, (long)getpid());
    if (length < 0 || (size_t)length >= sizeof(temporary)) { close(lock); return 1; }
    int file = open(temporary, O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    bool written = file >= 0 && !write_token(file, "meteor-cancel-v1") && !write_token(file, configuration.id)
            && !write_token(file, force ? "1" : "0") && !write_token(file, previous_error);
    if (file >= 0) { fsync(file); close(file); }
    if (!written || rename(temporary, control_path)) { perror("Cannot write cancellation control"); close(lock); return 1; }
    flock(lock, LOCK_UN); close(lock);
    if (previous_error[0]) fprintf(stderr, "%s\n", previous_error);

    // The immutable start tick and pidfd protect another invocation from PID reuse.
    bool notified = false, identity_changed = false;
    double deadline = monotonic() + 30;
    while (monotonic() < deadline) {
        tokens status;
        if (!read_tokens(state_path, &status, 65536)) {
            const char *id = status_field(&status, "processId");
            const char *state = status_field(&status, "state");
            if (!id || strcmp(id, configuration.id) || !state) {
                fprintf(stderr, "Status processId does not match launch\n"); free_tokens(&status); return 1;
            }
            if (!strcmp(state, "exited") || !strcmp(state, "failed")) {
                const char *empty = status_field(&status, "processGroupEmpty");
                const char *removed = status_field(&status, "temporaryDirectoryRemoved");
                bool cleaned = empty && removed && !strcmp(empty, "1") && !strcmp(removed, "1");
                int code = !strcmp(state, "exited") && cleaned && !previous_error[0] ? 0 : 1;
                free_tokens(&status); return code;
            }
            // A published terminal state precedes wrapper exit; recheck it once before rejecting a stale snapshot.
            if (identity_changed) {
                fprintf(stderr, "Peer wrapper identity no longer matches status\n"); free_tokens(&status); return 1;
            }
            const char *wrapper = status_field(&status, "wrapperPid");
            const char *ticks = status_field(&status, "wrapperStartTicks");
            identity current;
            pid_t pid = wrapper ? (pid_t)strtol(wrapper, NULL, 10) : 0;
            unsigned long long start = ticks ? strtoull(ticks, NULL, 10) : 0;
            if (pid <= 0 || !start || !process_identity(pid, &current) || current.ticks != start) {
                identity_changed = true;
                free_tokens(&status);
                continue;
            }
            if (!notified) {
                if (signal_identity(pid, start, SIGUSR1)) {
                    perror("Cannot notify owned peer wrapper"); free_tokens(&status); return 1;
                }
                notified = true;
            }
            free_tokens(&status);
        } else if (identity_changed || errno != ENOENT) { perror("Cannot read peer status"); return 1; }
        struct timespec pause = {.tv_sec = 0, .tv_nsec = 20000000};
        nanosleep(&pause, NULL);
    }
    fprintf(stderr, "Peer cancellation was not acknowledged within 30 seconds\n");
    return 1;
}

int main(int argc, char **argv) {
    if (argc < 3 || argc > 4 || (strcmp(argv[1], "--run") && strcmp(argv[1], "--stop"))
            || (argc == 4 && (strcmp(argv[1], "--stop") || strcmp(argv[3], "--force"))) || argv[2][0] != '/') {
        fprintf(stderr, "Usage: peer-process (--run|--stop) /absolute/launch.json [--force]\n"); return 2;
    }
    if (strlen(argv[2]) >= sizeof(evidence_directory)) { fprintf(stderr, "Peer launch path is too long\n"); return 2; }
    strcpy(evidence_directory, argv[2]);
    char *separator = strrchr(evidence_directory, '/');
    if (separator == evidence_directory) separator[1] = 0;
    else *separator = 0;
    if (load_launch()) { perror("Cannot read native peer launch"); return 1; }
    int result = !strcmp(argv[1], "--run") ? run_peer() : stop_peer(argc == 4);
    free(configuration.arguments);
    free_tokens(&configuration.input);
    return result;
}
