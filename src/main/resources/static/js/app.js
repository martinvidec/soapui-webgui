/* CodeMirror-Editoren initialisieren — auch nach HTMX-Swaps. */
function initCodeEditors(root) {
    (root || document).querySelectorAll('textarea.code-editor:not([data-cm])').forEach(function (area) {
        area.dataset.cm = '1';
        var editor = CodeMirror.fromTextArea(area, {
            mode: area.dataset.mode === 'json' ? {name: 'javascript', json: true}
                : (area.dataset.mode || 'xml'),
            lineNumbers: true,
            lineWrapping: true,
            readOnly: area.readOnly ? 'nocursor' : false,
            viewportMargin: Infinity
        });
        // Textarea vor jedem Form-Submit synchron halten (HTMX liest die Textarea)
        editor.on('change', function () {
            editor.save();
        });
    });
}

document.addEventListener('DOMContentLoaded', function () {
    initCodeEditors(document);
    document.body.addEventListener('htmx:afterSwap', function (evt) {
        initCodeEditors(evt.target);
    });
    // Editoren in <details> erst beim Aufklappen initialisieren/refreshen,
    // sonst rendert CodeMirror mit Breite 0
    document.addEventListener('toggle', function (evt) {
        if (!(evt.target instanceof HTMLElement) || !evt.target.open) {
            return;
        }
        initCodeEditors(evt.target);
        evt.target.querySelectorAll('.CodeMirror').forEach(function (el) {
            if (el.CodeMirror) {
                el.CodeMirror.refresh();
            }
        });
    }, true);
});
