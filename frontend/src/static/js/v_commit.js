const repoCache = [];
window.vCommit = {
    props: ['info'],
    template: window.$('v_commit').innerHTML,
    data() {
        return {
            isLoaded: false,
            diffs: [],
            hash: "",
            author: "",
            message: "",
            date: "",
            title: "",
            activeFilesCount: 0,
        };
    },
    methods: {
        initiate() {
            const repo = window.REPOS[this.info.repo];

            this.getRepoProps(repo);
            if (!repo || !this.info.author) {
                window.app.isTabActive = false;
                return;
            }
            if (repoCache.length === 2) {
                const toRemove = repoCache.shift();
                if (toRemove !== this.info.repo) {
                    delete window.REPOS[toRemove].files;
                }
            }
            repoCache.push(this.info.repo);
            this.loadFile(this.info.hash);
        },

        expandAll(isActive) {
            const renameValue = isActive ? 'file active' : 'file';

            const files = document.getElementsByClassName('file');
            Array.from(files).forEach((file) => {
                file.className = renameValue;
            });

            this.activeFilesCount = isActive ? this.selectedFiles.length : 0;
        },

        getRepoProps(repo) {
            if (repo) {
                const author = repo.users.filter((user) => user.name === this.info.author);
                if (author.length > 0) {
                    this.info.name = author[0].displayName;
                    this.filesLinesObj = author[0].fileTypeContribution;
                }
            }
        },

        loadFile(fileName) {
            const folderName = window.REPOS[this.info.repo].outputFolderName;
            loadJSON(`${REPORT_DIR}/${folderName}/${fileName}`)
                .then((files) => {
                    this.processCommit(files);
                });
        },

        updateCount() {
            this.activeFilesCount = document.getElementsByClassName('file active').length;
        },

        processCommit(files) {
            this.hash = files["hash"];
            this.author = files["author"] + " <" + files["email"] + ">";
            this.date = files["date"];
            this.title = files["title"];
            this.message = files["message"];
            this.diffs = files["diffs"];
            this.activeFilesCount = this.diffs.length;
            this.isLoaded = true;
        },
    },


    created() {
        this.initiate();
    },

    components: {
        v_hunk: window.vHunk,
    },
};
