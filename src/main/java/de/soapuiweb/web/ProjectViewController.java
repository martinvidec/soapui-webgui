package de.soapuiweb.web;

import com.eviware.soapui.model.ModelItem;
import de.soapuiweb.engine.ModelItems;
import de.soapuiweb.service.LockService;
import de.soapuiweb.service.ProjectHandle;
import de.soapuiweb.service.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Projekt-Arbeitsansicht: Baum links, Detail-Panel rechts (FA-05) plus
 * Sperren-Endpunkte (FA-06). Baum-Ebenen laden lazy via HTMX.
 */
@Controller
public class ProjectViewController {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final ProjectService projectService;
    private final LockService lockService;

    public ProjectViewController(ProjectService projectService, LockService lockService) {
        this.projectService = projectService;
        this.lockService = lockService;
    }

    public record TreeNode(String id, String name, String typeLabel, boolean hasChildren) {
        static TreeNode of(ModelItem item) {
            return new TreeNode(item.getId(), item.getName(), ModelItems.typeLabel(item),
                    !ModelItems.childrenOf(item).isEmpty());
        }
    }

    @GetMapping("/projects/{id}")
    public String view(@PathVariable String id, Authentication auth, Model model) {
        ProjectHandle handle = requireHandle(id);
        lockService.touch(id, auth.getName());
        fillLockModel(id, auth, model);
        model.addAttribute("projectId", id);
        model.addAttribute("projectName", handle.meta().name());
        model.addAttribute("roots",
                ModelItems.childrenOf(handle.project()).stream().map(TreeNode::of).toList());
        return "project/view";
    }

    @GetMapping("/projects/{id}/tree/{itemId}")
    public String treeChildren(@PathVariable String id, @PathVariable String itemId,
                               Authentication auth, Model model) {
        ProjectHandle handle = requireHandle(id);
        lockService.touch(id, auth.getName());
        ModelItem item = requireItem(handle, itemId);
        List<TreeNode> nodes;
        handle.lock().readLock().lock();
        try {
            nodes = ModelItems.childrenOf(item).stream().map(TreeNode::of).toList();
        } finally {
            handle.lock().readLock().unlock();
        }
        model.addAttribute("projectId", id);
        model.addAttribute("nodes", nodes);
        return "project/tree :: children";
    }

    @GetMapping("/projects/{id}/items/{itemId}")
    public String itemDetail(@PathVariable String id, @PathVariable String itemId,
                             Authentication auth, Model model) {
        ProjectHandle handle = requireHandle(id);
        lockService.touch(id, auth.getName());
        ModelItem item = requireItem(handle, itemId);
        model.addAttribute("name", item.getName());
        model.addAttribute("typeLabel", ModelItems.typeLabel(item));
        model.addAttribute("itemId", item.getId());
        model.addAttribute("childCount", ModelItems.childrenOf(item).size());
        model.addAttribute("description", item.getDescription());
        return "project/detail :: panel";
    }

    @PostMapping("/projects/{id}/lock")
    public String acquireLock(@PathVariable String id, Authentication auth,
                              RedirectAttributes redirect) {
        requireHandle(id);
        try {
            lockService.acquire(id, auth.getName());
        } catch (LockService.LockConflictException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    @PostMapping("/projects/{id}/unlock")
    public String releaseLock(@PathVariable String id,
                              @RequestParam(defaultValue = "false") boolean force,
                              Authentication auth, RedirectAttributes redirect) {
        requireHandle(id);
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        try {
            lockService.release(id, auth.getName(), force && isAdmin);
        } catch (LockService.LockConflictException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/projects/" + id;
    }

    private void fillLockModel(String id, Authentication auth, Model model) {
        var lock = lockService.ownerLock(id).orElse(null);
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        model.addAttribute("lockOwner", lock == null ? null : lock.owner());
        model.addAttribute("lockExpiresAt",
                lock == null ? null : TIME_FORMAT.format(lock.expiresAt()));
        model.addAttribute("heldByMe", lock != null && lock.owner().equals(auth.getName()));
        model.addAttribute("isAdmin", isAdmin);
    }

    private ProjectHandle requireHandle(String id) {
        return projectService.find(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unbekanntes Projekt"));
    }

    private ModelItem requireItem(ProjectHandle handle, String itemId) {
        return ModelItems.findById(handle.project(), itemId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unbekanntes Element"));
    }
}
