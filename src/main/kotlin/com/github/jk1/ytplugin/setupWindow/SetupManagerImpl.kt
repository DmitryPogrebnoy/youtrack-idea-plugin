package com.github.jk1.ytplugin.setupWindow

import com.github.jk1.ytplugin.setupWindow.Connection.CancellableConnection
import com.intellij.configurationStore.deserialize
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.util.Comparing
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsTaskHandler
import com.intellij.openapi.vcs.VcsType
import com.intellij.openapi.vcs.changes.ChangeList
import com.intellij.openapi.vcs.changes.ChangeListAdapter
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.LocalChangeList
import com.intellij.openapi.vcs.changes.shelf.ShelveChangesManager
import com.intellij.serialization.SerializationException
import com.intellij.tasks.*
import com.intellij.tasks.context.WorkingContextManager
import com.intellij.tasks.impl.*
import com.intellij.tasks.impl.TaskProjectConfiguration.SharedServer
import com.intellij.tasks.youtrack.YouTrackRepository
import com.intellij.tasks.youtrack.YouTrackRepositoryType
import com.intellij.util.ArrayUtilRt
import com.intellij.util.EventDispatcher
import com.intellij.util.Function
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.containers.Convertor
import com.intellij.util.containers.MultiMap
import com.intellij.util.io.HttpRequests
import com.intellij.util.ui.TimerUtil
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Property
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import org.jdom.Element
import org.jetbrains.annotations.TestOnly
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.DecimalFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.Timer


/**
 * @author Dmitry Avdeev
 */
@State(name = "SetupManager", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SetupManagerImpl(private val myProject: Project) : SetupManager(), PersistentStateComponent<SetupManagerImpl.Config>, Disposable {
    private val myIssueCache = Collections.synchronizedMap(LinkedHashMap<String, Task>())

    private val myTasks: MutableMap<String?, LocalTask?>? = Collections.synchronizedMap(object : LinkedHashMap<String?, LocalTask?>() {
        fun put(key: String, task: LocalTask): LocalTask? {
            val result = super.put(key, task)!!
            if (size > myConfig.taskHistoryLength) {
                val list: ArrayList<MutableMap.MutableEntry<String?, LocalTask?>> = ArrayList(entries)
                Collections.sort(list, Comparator { o1: MutableMap.MutableEntry<String?, LocalTask?>, o2: MutableMap.MutableEntry<String?, LocalTask?> -> TaskManagerImpl.TASK_UPDATE_COMPARATOR.compare(o2.value, o1.value) })
                for (oldest in list) {
                    if (!oldest.value!!.isDefault()) {
                        remove(oldest.key)
                        break
                    }
                }
            }
            return result
        }
    })

    private var myActiveTask: LocalTask = createDefaultTask()
    private var myCacheRefreshTimer: Timer? = null

    @Volatile
    private var myUpdating = false
    private val myConfig = Config()

    @get:TestOnly
    val changeListListener: ChangeListAdapter
    private val myRepositories: MutableList<YouTrackRepository> = ArrayList()
    private val myDispatcher = EventDispatcher.create(TaskListener::class.java)
    private val myBadRepositories = ContainerUtil.newConcurrentSet<YouTrackRepository>()

    @TestOnly
    fun prepareForNextTest() {
        myTasks?.clear()
        val defaultTask = createDefaultTask()
        addTask(defaultTask)
        myActiveTask = defaultTask
        var set: MutableList<YouTrackRepository> = ArrayList()
        setRepositories(set)
    }

    override fun getAllRepositories(): Array<YouTrackRepository> {
        return myRepositories.toTypedArray()
    }

    fun setRepositories(repositories: List<YouTrackRepository>) {
        val set: MutableSet<YouTrackRepository> = HashSet(myRepositories)
        set.removeAll(repositories)
        myBadRepositories.removeAll(set) // remove all changed reps
        myIssueCache.clear()
        myRepositories.clear()
        myRepositories.addAll(repositories)
        val servers = projectConfiguration.servers
        servers.clear()
        reps@ for (repository in repositories) {
            if (repository.isShared && repository.url != null) {
                val type = repository.repositoryType
                for (server in servers) {
                    if (repository.url == server.url && type.name == server.type) {
                        continue@reps
                    }
                }
                val server = SharedServer()
                server.type = type.name
                server.url = repository.url
                servers.add(server)
            }
        }
        clearNonExistentRepositoriesFromTasks()
    }

    private fun clearNonExistentRepositoriesFromTasks() {
        if (myTasks != null) {
            for (task in myTasks.values) {
                val repository = task?.repository
                if (repository != null && !myRepositories.contains(repository) && task is LocalTaskImpl) {
                    task.repository = null
                }
            }
        }
    }

    override fun removeTask(task: LocalTask) {
        if (task.isDefault) {
            return
        }
        if (myActiveTask == task) {
            activateTask(this.myTasks?.get(LocalTaskImpl.DEFAULT_TASK_ID)!!, true)
        }
        myTasks?.remove(task.id)
        myDispatcher.multicaster.taskRemoved(task)
        WorkingContextManager.getInstance(myProject).removeContext(task)
    }

    override fun addTaskListener(listener: TaskListener) {
        myDispatcher.addListener(listener)
    }

    override fun addTaskListener(listener: TaskListener, parentDisposable: Disposable) {
        myDispatcher.addListener(listener, parentDisposable)
    }

    override fun removeTaskListener(listener: TaskListener) {
        myDispatcher.removeListener(listener)
    }

    override fun getActiveTask(): LocalTask {
        return myActiveTask
    }

    override fun findTask(id: String): LocalTask? {
        return myTasks?.get(id)
    }

    override fun getIssues(query: String?): List<Task> {
        return getIssues(query, true)
    }

    override fun getIssues(query: String?, forceRequest: Boolean): List<Task> {
        return getIssues(query, 0, 50, true, EmptyProgressIndicator(), forceRequest)
    }

    override fun getIssues(query: String?,
                           offset: Int,
                           limit: Int,
                           withClosed: Boolean,
                           indicator: ProgressIndicator,
                           forceRequest: Boolean): List<Task> {
        val tasks = getIssuesFromRepositories(query, offset, limit, withClosed, forceRequest, indicator)
                ?: return getCachedIssues(withClosed)
        myIssueCache.putAll(ContainerUtil.newMapFromValues(tasks.iterator(), KEY_CONVERTOR))
        return ContainerUtil.filter(tasks) { task: Task -> withClosed || !task.isClosed }
    }

    override fun getCachedIssues(): List<Task> {
        return getCachedIssues(true)
    }

    override fun getCachedIssues(withClosed: Boolean): List<Task> {
        return ContainerUtil.filter(myIssueCache.values) { task: Task -> withClosed || !task.isClosed }
    }


    override fun updateIssue(id: String) {
        for (repository in getAllRepositories()) {
            if (repository.extractId(id) == null) {
                continue
            }
            try {
                LOG.info("Searching for task '$id' in $repository")
                val issue = repository.findTask(id)
                if (issue != null) {
                    val localTask = findTask(id)
                    if (localTask != null) {
                        localTask.updateFromIssue(issue)
                        return
                    }
                    return
                }
            } catch (e: Exception) {
                LOG.info(e)
            }
        }
    }

    override fun getLocalTasks(): MutableList<LocalTask?> {
        return getLocalTasks(true)
    }

    override fun getLocalTasks(withClosed: Boolean): MutableList<LocalTask?> {
        synchronized(myTasks!!) { return ContainerUtil.filter<LocalTask>(myTasks.values) { task: LocalTask? -> withClosed || !isLocallyClosed(task!!) } }
    }

    override fun addTask(issue: Task): LocalTask {
        val task = if (issue is LocalTaskImpl) issue else LocalTaskImpl(issue)
        addTask(task)
        return task
    }

    override fun createLocalTask(summary: String): LocalTaskImpl {
        return createTask(LOCAL_TASK_ID_FORMAT.format(myConfig.localTasksCounter++.toLong()), summary)
    }

    override fun activateTask(task: Task, clearContext: Boolean): LocalTask {
        return activateTask(task, clearContext, false)
    }

    fun activateTask(origin: Task, clearContext: Boolean, newTask: Boolean): LocalTask {
        val activeTask = getActiveTask()
        if (origin == activeTask) return activeTask
        saveActiveTask()
        val task = doActivate(origin, true)
        val restore = Runnable {
            val contextManager = WorkingContextManager.getInstance(myProject)
            if (clearContext) {
                contextManager.clearContext()
            }
            contextManager.restoreContext(origin)
        }
        var switched = false
        if (isVcsEnabled()) {
            restoreVcsContext(task, newTask)
            if (!newTask) {
                switched = switchBranch(task, restore)
            }
        }
        if (!switched) restore.run()
        return task
    }
    private fun restoreVcsContext(task: LocalTask, newTask: Boolean) {
        val changeLists = task.changeLists
        val changeListManager = ChangeListManager.getInstance(myProject)
        if (changeLists.isEmpty()) {
            task.addChangelist(ChangeListInfo(changeListManager.defaultChangeList))
        } else {
            val info = changeLists[0]
            var changeList = changeListManager.getChangeList(info.id)
            if (changeList == null) {
                changeList = changeListManager.addChangeList(info.name, info.comment)
                info.id = changeList.id
            }
            changeListManager.defaultChangeList = changeList!!
        }
        unshelveChanges(task)
    }

    private fun switchBranch(task: LocalTask, invokeAfter: Runnable): Boolean {
        val branches = task.getBranches(false)
        // we should have exactly one branch per repo
        val multiMap = MultiMap<String, BranchInfo>()
        for (branch in branches) {
            multiMap.putValue(branch.repository, branch)
        }
        for (repo in multiMap.keySet()) {
            val infos = multiMap[repo]
            if (infos.size > 1) {
                // cleanup needed
                val existing = getAllBranches(repo)
                val iterator = infos.iterator()
                while (iterator.hasNext()) {
                    val info = iterator.next()
                    if (!existing.contains(info)) {
                        iterator.remove()
                        if (infos.size == 1) {
                            break
                        }
                    }
                }
            }
        }
        val info = fromBranches(ArrayList(multiMap.values()))
        val handlers = VcsTaskHandler.getAllHandlers(myProject)
        var switched = false
        for (handler in handlers) {
            switched = switched or handler.switchToTask(info, invokeAfter)
        }
        return switched
    }

    fun shelveChanges(task: LocalTask, shelfName: String) {
        val changes = ChangeListManager.getInstance(myProject).defaultChangeList.changes
        if (changes.isEmpty()) return
        try {
            ShelveChangesManager.getInstance(myProject).shelveChanges(changes, shelfName, true)
            task.shelfName = shelfName
        } catch (e: Exception) {
            LOG.warn("Can't shelve changes", e)
        }
    }

    private fun unshelveChanges(task: LocalTask) {
        val name = task.shelfName
        if (name != null) {
            val manager = ShelveChangesManager.getInstance(myProject)
            val changeListManager = ChangeListManager.getInstance(myProject)
            for (list in manager.shelvedChangeLists) {
                if (name == list.DESCRIPTION) {
                    manager.unshelveChangeList(list, null, list.binaryFiles, changeListManager.defaultChangeList, true)
                    return
                }
            }
        }
    }

    private fun getAllBranches(repo: String): List<BranchInfo> {
        val infos = ArrayList<BranchInfo>()
        val handlers = VcsTaskHandler.getAllHandlers(myProject)
        for (handler in handlers) {
            val tasks = handler.allExistingTasks
            for (info in tasks) {
                infos.addAll(ContainerUtil.filter(BranchInfo.fromTaskInfo(info, false)) { info1: BranchInfo -> info1.repository == repo })
            }
        }
        return infos
    }

     fun createBranch(task: LocalTask, previousActive: LocalTask?, name: String?, branchFrom: VcsTaskHandler.TaskInfo?) {
        val handlers = VcsTaskHandler.getAllHandlers(myProject)
        for (handler in handlers) {
            val info = handler.currentTasks
            if (previousActive != null && previousActive.getBranches(false).isEmpty()) {
                addBranches(previousActive, info, false)
            }
            addBranches(task, info, true)
            if (info.size == 0 && branchFrom != null) {
                addBranches(task, arrayOf(branchFrom), true)
            }
            addBranches(task, arrayOf(handler.startNewTask(name!!)), false)
        }
    }

     fun mergeBranch(task: LocalTask) {
        val original = fromBranches(task.getBranches(true))
        val feature = fromBranches(task.getBranches(false))
        val handlers = VcsTaskHandler.getAllHandlers(myProject)
        for (handler in handlers) {
            handler.closeTask(feature, original)
        }
    }

    private fun saveActiveTask() {
        WorkingContextManager.getInstance(myProject).saveContext(myActiveTask)
        myActiveTask.updated = Date()
        val shelfName = myActiveTask.shelfName
        shelfName?.let { shelveChanges(myActiveTask, it) }
    }

    private fun doActivate(origin: Task, explicitly: Boolean): LocalTask {
        val task = if (origin is LocalTaskImpl) origin else LocalTaskImpl(origin)
        if (explicitly) {
            task.updated = Date()
        }
        myActiveTask.isActive = false
        task.isActive = true
        addTask(task)
        if (task.isIssue) {
            StartupManager.getInstance(myProject).runWhenProjectIsInitialized {
                ProgressManager.getInstance().run(object : Backgroundable(myProject, TaskBundle
                        .message("progress.title.updating", task.presentableId)) {
                    override fun run(indicator: ProgressIndicator) {
                        updateIssue(task.id)
                    }
                })
            }
        }
        val oldActiveTask = myActiveTask
        val isChanged = task != oldActiveTask
        myActiveTask = task
        if (isChanged) {
            myDispatcher.multicaster.taskDeactivated(oldActiveTask)
            myDispatcher.multicaster.taskActivated(task)
        }
        return task
    }

    private fun addTask(task: LocalTaskImpl) {
        myTasks?.set(task.id, task)
        myDispatcher.multicaster.taskAdded(task)

    }

    override fun testConnection(repository: YouTrackRepository): Boolean {
        val task: TestConnectionTask = object : TestConnectionTask("Test connection", myProject) {
            override fun run(indicator: ProgressIndicator) {
                indicator.fraction = 0.0
                indicator.isIndeterminate = true
                try {
                    val setupTask = SetupTask()
                    myConnection = setupTask.createCancellableConnection(repository)
                    if (myConnection != null) {
                        val future = ApplicationManager.getApplication().executeOnPooledThread(myConnection!!)
                        while (true) {
                            try {
                                myException = future[100, TimeUnit.MILLISECONDS]
                                return
                            } catch (ignore: TimeoutException) {
                                try {
                                    indicator.checkCanceled()
                                } catch (e: ProcessCanceledException) {
                                    myException = e
                                    myConnection!!.cancel()
                                    return
                                }
                            } catch (e: Exception) {
                                myException = e
                                return
                            }
                        }
                    } else {
                        try {
                            repository.testConnection()
                        } catch (e: Exception) {
                            LOG.info(e)
                            myException = e
                        }
                    }
                } catch (e: Exception) {
                    myException = e
                }
            }
        }
        ProgressManager.getInstance().run(task)
        val e = task.myException
        if (e == null) {
            myBadRepositories.remove(repository)
        } else if (e !is ProcessCanceledException) {
            var message = e.message
            if (e is UnknownHostException) {
                message = "Unknown host: $message"
            }
            if (message == null) {
                LOG.error(e)
                message = "Unknown error"
            }
        }
        return e == null
    }

//    override fun getState(): Config {
//        myConfig.tasks = ContainerUtil.map(myTasks.values, Function { task: Task? -> LocalTaskImpl(task) } as Function<Task?, LocalTaskImpl>)
//        myConfig.servers = serialize.serialize(allRepositories)
//        return myConfig
//    }

    override fun getState(): Config {
        myConfig.tasks = ContainerUtil.map<LocalTask, LocalTaskImpl>(myTasks!!.values, Function { task: Task? -> LocalTaskImpl(task) } as Function<Task?, LocalTaskImpl>)
        myConfig.servers = getAllRepositories()?.let { XmlSerializer.serialize(it) }!!;
        return myConfig
    }

    fun updateToVelocity(format: String): String {
        return format.replace("\\{".toRegex(), "\\$\\{").replace("\\$\\$\\{".toRegex(), "\\$\\{")
    }

    override fun loadState(config: Config) {
        config.branchNameFormat = updateToVelocity(config.branchNameFormat)
        config.changelistNameFormat = config.changelistNameFormat?.let { updateToVelocity(it) }
        XmlSerializerUtil.copyBean(config, myConfig)
        myRepositories.clear()
        val element = config.servers
        val repositories: MutableList<YouTrackRepository> = loadRepositories(element)
        myRepositories.addAll(repositories)
        myTasks?.clear()
        for (task in config.tasks) {
            if (task.repository == null) {
                // restore repository from url
                val url = task.issueUrl
                if (url != null) {
                    for (repository in repositories) {
                        if (repository.url != null && url.startsWith(repository.url)) {
                            task.setRepository(repository)
                        }
                    }
                }
            }
            addTask(task)
        }
    }

    @TestOnly
    fun callProjectOpened() {
        projectOpened()
    }

    fun projectOpened() {
        val projectConfiguration = projectConfiguration
        servers@ for (server in projectConfiguration.servers) {
            if (server.type == null || server.url == null) {
                continue
            }
            for (repositoryType in YouTrackRepositoryType.getRepositoryTypes()) {
                if (repositoryType.name == server.type) {
                    for (repository in myRepositories) {
                        if (repositoryType != repository.repositoryType) {
                            continue
                        }
                        if (server.url == repository.url) {
                            continue@servers
                        }
                    }
                    val repository = repositoryType.createRepository()
                    repository.url = server.url
                    repository.isShared = true
                    myRepositories.add(repository as YouTrackRepository)
                }
            }
        }

        // make sure the task is associated with default changelist
        val defaultTask = findTask(LocalTaskImpl.DEFAULT_TASK_ID)
        val changeListManager = ChangeListManager.getInstance(myProject)
        val defaultList = changeListManager.findChangeList(LocalChangeList.getDefaultName())
        if (defaultList != null && defaultTask != null) {
            val listInfo = ChangeListInfo(defaultList)
            if (!defaultTask.changeLists.contains(listInfo)) {
                defaultTask.addChangelist(listInfo)
            }
        }

        // remove already not existing changelists from tasks changelists
        for (localTask in getLocalTasks()) {
            val iterator = localTask?.changeLists?.iterator()
            while (iterator?.hasNext()!!) {
                val changeListInfo = iterator.next()
                if (changeListManager.getChangeList(changeListInfo.id) == null) {
                    iterator.remove()
                }
            }
        }
        changeListManager.addChangeListListener(changeListListener, myProject)
        if (!ApplicationManager.getApplication().isUnitTestMode) {
            ApplicationManager.getApplication().executeOnPooledThread { WorkingContextManager.getInstance(myProject).pack(200, 50) }
        }
        if (!ApplicationManager.getApplication().isUnitTestMode) {
            startRefreshTimer()
        }
    }

    private val projectConfiguration: TaskProjectConfiguration
        get() = ServiceManager.getService(myProject, TaskProjectConfiguration::class.java)

    override fun initializeComponent() {
        // make sure that the default task is exist
        var defaultTask = findTask(LocalTaskImpl.DEFAULT_TASK_ID)
        if (defaultTask == null) {
            defaultTask = createDefaultTask()
            addTask(defaultTask)
        }

        // search for active task
        var activeTask: LocalTask? = null
        val tasks = getLocalTasks()
        tasks.sortWith(TASK_UPDATE_COMPARATOR)
        for (task in tasks) {
            if (activeTask == null) {
                if (task != null) {
                    if (task.isActive) {
                        activeTask = task
                    }
                }
            } else {
                if (task != null) {
                    task.isActive = false
                }
            }
        }
        if (activeTask == null) {
            activeTask = defaultTask
        }
        myActiveTask = activeTask
        doActivate(myActiveTask, false)
        myDispatcher.multicaster.taskActivated(myActiveTask)
    }

    fun startRefreshTimer() {
        myCacheRefreshTimer = TimerUtil.createNamedTimer("TaskManager refresh", myConfig.updateInterval * 60 * 1000) {
            if (myConfig.updateEnabled && !myUpdating) {
                LOG.debug("Updating issues cache (every " + myConfig.updateInterval + " min)")
                updateIssues(null)
            }
        }
        myCacheRefreshTimer!!.initialDelay = 0
        myCacheRefreshTimer!!.start()
    }

    override fun dispose() {
        if (myCacheRefreshTimer != null) {
            myCacheRefreshTimer!!.stop()
        }
    }

    fun updateIssues(onComplete: Runnable?) {
        val first = ContainerUtil.find<YouTrackRepository>(getAllRepositories()) { repository: YouTrackRepository -> repository.isConfigured }
        if (first == null) {
            myIssueCache.clear()
            onComplete?.run()
            return
        }
        myUpdating = true
        if (ApplicationManager.getApplication().isUnitTestMode) {
            doUpdate(onComplete)
        } else {
            ApplicationManager.getApplication().executeOnPooledThread { doUpdate(onComplete) }
        }
    }

    private fun doUpdate(onComplete: Runnable?) {
        try {
            val issues = getIssuesFromRepositories(null, 0, myConfig.updateIssuesCount, false, false, EmptyProgressIndicator())
                    ?: return
            synchronized(myIssueCache) {
                myIssueCache.clear()
                for (issue in issues) {
                    myIssueCache[issue.id] = issue
                }
            }
            // update local tasks
            if (myTasks != null) {
                synchronized(myTasks) {
                    for ((key, value) in myTasks) {
                        val issue = myIssueCache[key]
                        if (issue != null) {
                            value?.updateFromIssue(issue)
                        }
                    }
                }
            }
        } finally {
            onComplete?.run()
            myUpdating = false
        }
    }

    private fun getIssuesFromRepositories(request: String?,
                                          offset: Int,
                                          limit: Int,
                                          withClosed: Boolean,
                                          forceRequest: Boolean,
                                          cancelled: ProgressIndicator): List<Task>? {
        var issues: MutableList<Task>? = null
        if (getAllRepositories() != null) {
            for (repository in getAllRepositories()) {
                if (!repository.isConfigured || !forceRequest && myBadRepositories.contains(repository)) {
                    continue
                }
                try {
                    val start = System.currentTimeMillis()
                    val tasks = repository.getIssues(request, offset, limit, withClosed, cancelled)
                    val timeSpent = System.currentTimeMillis() - start
                    LOG.debug(String.format("Total %s ms to download %d issues from '%s' (pattern '%s')",
                            timeSpent, tasks?.size, repository?.url, request))
                    myBadRepositories.remove(repository)
                    if (issues == null) issues = tasks?.size?.let { ArrayList(it) }
                    if (!repository.isSupported(TaskRepository.NATIVE_SEARCH) && request != null) {
                        val filteredTasks = TaskUtil.filterTasks(request, Arrays.asList(*tasks))
                        issues?.addAll(filteredTasks)
                    } else {
                        if (tasks != null && issues != null) {
                            ContainerUtil.addAll<Task, MutableList<Task>>(issues, *tasks)
                        }
                    }
                } catch (ignored: ProcessCanceledException) {
                    // OK
                } catch (e: Exception) {
                    var reason: String? = ""
                    // Fix to IDEA-111810
                    if (e.javaClass == Exception::class.java || e is RequestFailedException) {
                        // probably contains some message meaningful to end-user
                        reason = e.message
                    }
                    if (e is SocketTimeoutException || e is HttpRequests.HttpStatusException) {
                        LOG.warn("Can't connect to " + repository + ": " + e.message)
                    } else {
                        LOG.warn("Cannot connect to $repository", e)
                    }
                    myBadRepositories.add(repository)
                    if (forceRequest) {
                        throw RequestFailedException(repository, reason)
                    }
                }
            }
        }
        return issues
    }

    override fun isVcsEnabled(): Boolean {
        return ProjectLevelVcsManager.getInstance(myProject).allActiveVcss.size > 0
    }

    override fun getActiveVcs(): AbstractVcs? {
        val vcss = ProjectLevelVcsManager.getInstance(myProject).allActiveVcss
        if (vcss.size == 0) return null
        for (vcs in vcss) {
            if (vcs.type == VcsType.distributed) {
                return vcs
            }
        }
        return vcss[0]
    }

    override fun isLocallyClosed(localTask: LocalTask): Boolean {
        if (!isVcsEnabled()) {
            return false
        }
        val lists = localTask.changeLists
        return lists.isEmpty() || lists.stream().anyMatch { list: ChangeListInfo -> StringUtil.isEmpty(list.id) }
    }

    override fun getAssociatedTask(list: LocalChangeList): LocalTask? {
        if (hasChangelist(getActiveTask(), list)) return getActiveTask()
        for (task in getLocalTasks()) {
            if (task?.let { hasChangelist(it, list) }!!) return task
        }
        return null
    }

    override fun trackContext(changeList: LocalChangeList) {
        val changeListInfo = ChangeListInfo(changeList)
        val changeListName = changeList.name
        val task = createLocalTask(changeListName)
        task.addChangelist(changeListInfo)
        addTask(task)
        if (changeList.isDefault) {
            activateTask(task, false)
        }
    }

    private fun createChangeList(task: LocalTask, name: String) {
        val comment = TaskUtil.getChangeListComment(task)
        createChangeList(task, name, comment)
    }

    private fun createChangeList(task: LocalTask, name: String, comment: String?) {
        val changeListManager = ChangeListManager.getInstance(myProject)
        var changeList = changeListManager.findChangeList(name)
        if (changeList == null) {
            changeList = changeListManager.addChangeList(name, comment)
        } else {
            val associatedTask = getAssociatedTask(changeList)
            associatedTask?.removeChangelist(ChangeListInfo(changeList))
            changeListManager.editComment(name, comment)
        }
        task.addChangelist(ChangeListInfo(changeList!!))
        changeListManager.defaultChangeList = changeList
    }

     fun getChangelistName(task: Task): String {
        val name = if (task.isIssue && myConfig.changelistNameFormat != null) TaskUtil.formatTask(task, myConfig.changelistNameFormat) else task.summary
        return StringUtil.shortenTextWithEllipsis(name, 100, 0)
    }

    @JvmOverloads
    fun suggestBranchName(task: Task, separator: String = "-"): String {
        val name = constructDefaultBranchName(task)
        if (task.isIssue) return name.replace(" ", separator)
        val words = StringUtil.getWordsIn(name)
        val strings = ArrayUtilRt.toStringArray(words)
        return StringUtil.join(strings, 0, Math.min(2, strings.size), separator)
    }

    fun constructDefaultBranchName(task: Task): String {
        return if (task.isIssue) TaskUtil.formatTask(task, myConfig.branchNameFormat) else task.summary
    }

    class Config {
        @Property(surroundWithTag = false)
        @XCollection(elementName = "task")
        var tasks: List<LocalTaskImpl> = ArrayList()
        var localTasksCounter = 1

        @ReportValue
        var taskHistoryLength = 50
        var updateEnabled = true

        @ReportValue
        var updateInterval = 20

        @ReportValue
        var updateIssuesCount = 100

        // create task options
        var clearContext = true
        var createChangelist = true
        var createBranch = true
        var useBranch = false
        var shelveChanges = false

        // close task options
        var commitChanges = true
        var mergeBranch = true
        var saveContextOnCommit = true
        var trackContextForNewChangelist = false
        var changelistNameFormat: String? = "\${id} \${summary}"
        var branchNameFormat = "\${id}"
        var searchClosedTasks = false

        @Tag("servers")
        var servers = Element("servers")
    }


    abstract class TestConnectionTask internal constructor(title: String?, project: Project?) : com.intellij.openapi.progress.Task.Modal(project, title!!, true) {
        var myException: java.lang.Exception? = null
        protected var myConnection: CancellableConnection? = null
        override fun onCancel() {
            if (myConnection != null) {
                myConnection!!.cancel()
            }
        }
    }

    private class Activity : StartupActivity.DumbAware {
        override fun runActivity(project: Project) {
            (getManager(project) as SetupManagerImpl).projectOpened()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SetupManagerImpl::class.java)
        private val LOCAL_TASK_ID_FORMAT = DecimalFormat("LOCAL-00000")
        val TASK_UPDATE_COMPARATOR = Comparator { o1: Task, o2: Task ->
            val i = Comparing.compare(o2.updated, o1.updated)
            if (i == 0) Comparing.compare(o2.created, o1.created) else i
        }
        private val KEY_CONVERTOR = Convertor { o: Task -> o.id }
        private fun createTask(id: String, summary: String): LocalTaskImpl {
            val task = LocalTaskImpl(id, summary)
            val date = Date()
            task.created = date
            task.updated = date
            return task
        }

        private fun fromBranches(branches: List<BranchInfo>): VcsTaskHandler.TaskInfo {
            if (branches.isEmpty()) return VcsTaskHandler.TaskInfo(null, emptyList())
            val map = MultiMap<String, String>()
            for (branch in branches) {
                map.putValue(branch.name, branch.repository)
            }
            val next: MutableMap.MutableEntry<String, MutableCollection<String>>? = map.entrySet().iterator().next()
            return VcsTaskHandler.TaskInfo(next?.key, next?.value)
        }

        fun addBranches(task: LocalTask, info: Array<VcsTaskHandler.TaskInfo?>, original: Boolean) {
            for (taskInfo in info) {
                val branchInfos = BranchInfo.fromTaskInfo(taskInfo, original)
                for (branchInfo in branchInfos) {
                    task.addBranch(branchInfo)
                }
            }
        }

        fun loadRepositories(element: Element): ArrayList<YouTrackRepository> {
            val repositories = ArrayList<YouTrackRepository>()
            for (repositoryType in TaskRepositoryType.getRepositoryTypes()) {
                for (o in element.getChildren(repositoryType.name)) {
                    try {
                        val repository = o.deserialize(repositoryType.getRepositoryClass()) as YouTrackRepository
                        repository.repositoryType = repositoryType
                        repository.initializeRepository()
                        repositories.add(repository)
                    } catch (e: SerializationException) {
                        LOG.error(e.message, e)
                    }
                }
            }
            return repositories
        }

        private fun createDefaultTask(): LocalTaskImpl {
            val task = LocalTaskImpl(LocalTaskImpl.DEFAULT_TASK_ID, "Default task")
            val date = Date()
            task.created = date
            task.updated = date
            return task
        }

        private fun hasChangelist(task: LocalTask, list: LocalChangeList): Boolean {
            for (changeListInfo in ArrayList(task.changeLists)) {
                if (changeListInfo.id == list.id) {
                    return true
                }
            }
            return false
        }
    }

    init {
        changeListListener = object : ChangeListAdapter() {
            override fun changeListRemoved(list: ChangeList) {
                val task = getAssociatedTask(list as LocalChangeList)
                if (task != null) {
                    for (info in task.changeLists) {
                        if (info.id == list.id) {
                            info.id = ""
                        }
                    }
                }
            }

            override fun defaultListChanged(oldDefaultList: ChangeList, newDefaultList: ChangeList, automatic: Boolean) {
                if (automatic) return
                val associatedTask = getAssociatedTask(newDefaultList as LocalChangeList)
                if (associatedTask != null && getActiveTask() != associatedTask) {
                    ApplicationManager.getApplication().invokeLater(Runnable { activateTask(associatedTask, true) }, myProject.disposed)
                }
            }
        }
        myProject.messageBus.connect().subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
            override fun projectOpened(project: Project) {
                if (myProject === project) {
                    projectOpened()
                }
            }
        })
    }
}
