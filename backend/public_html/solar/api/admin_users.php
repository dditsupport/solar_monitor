<?php
// Admin-only user CRUD. CSRF-protected POST.
//   action=list       -> GET-like JSON list of users
//   action=create     -> create user (username, password, email, is_admin)
//   action=set_password -> reset password for user_id
//   action=set_admin  -> toggle is_admin
//   action=delete     -> delete user

declare(strict_types=1);
require_once __DIR__ . '/_db.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    json_response(405, ['ok' => false, 'error' => 'method_not_allowed']);
}
$me = require_admin();
check_csrf();

$pdo    = db();
$action = (string)($_POST['action'] ?? '');

switch ($action) {

case 'list':
    $rows = $pdo->query(
        'SELECT u.id, u.username, u.email, u.is_admin, u.created_at, u.last_login_at,
                (SELECT COUNT(*) FROM devices d WHERE d.owner_user_id = u.id) AS device_count
           FROM users u ORDER BY u.username'
    )->fetchAll();
    json_response(200, ['ok' => true, 'users' => $rows]);

case 'create':
    $username = trim((string)($_POST['username'] ?? ''));
    $password = (string)($_POST['password'] ?? '');
    $email    = trim((string)($_POST['email'] ?? '')) ?: null;
    $is_admin = !empty($_POST['is_admin']) ? 1 : 0;
    if (!preg_match('/^[a-zA-Z0-9._-]{3,32}$/', $username)) {
        json_response(400, ['ok' => false, 'error' => 'bad_username']);
    }
    if (strlen($password) < 8) {
        json_response(400, ['ok' => false, 'error' => 'password_too_short']);
    }
    try {
        $pdo->prepare(
            'INSERT INTO users (username, password_hash, email, is_admin) VALUES (?, ?, ?, ?)'
        )->execute([$username, password_hash($password, PASSWORD_DEFAULT), $email, $is_admin]);
    } catch (PDOException $e) {
        $code = ($e->getCode() === '23000') ? 409 : 500;
        $err  = ($code === 409) ? 'username_taken' : 'db_error';
        json_response($code, ['ok' => false, 'error' => $err]);
    }
    json_response(200, ['ok' => true, 'id' => (int)$pdo->lastInsertId()]);

case 'set_password':
    $user_id  = (int)($_POST['user_id'] ?? 0);
    $password = (string)($_POST['password'] ?? '');
    if ($user_id <= 0 || strlen($password) < 8) {
        json_response(400, ['ok' => false, 'error' => 'bad_input']);
    }
    $pdo->prepare('UPDATE users SET password_hash = ? WHERE id = ?')
        ->execute([password_hash($password, PASSWORD_DEFAULT), $user_id]);
    json_response(200, ['ok' => true]);

case 'set_admin':
    $user_id  = (int)($_POST['user_id'] ?? 0);
    $is_admin = !empty($_POST['is_admin']) ? 1 : 0;
    if ($user_id <= 0) json_response(400, ['ok' => false, 'error' => 'bad_input']);
    if ($user_id === (int)$me['id'] && !$is_admin) {
        json_response(400, ['ok' => false, 'error' => 'cannot_demote_self']);
    }
    $pdo->prepare('UPDATE users SET is_admin = ? WHERE id = ?')
        ->execute([$is_admin, $user_id]);
    json_response(200, ['ok' => true]);

case 'delete':
    $user_id = (int)($_POST['user_id'] ?? 0);
    if ($user_id <= 0 || $user_id === (int)$me['id']) {
        json_response(400, ['ok' => false, 'error' => 'bad_input']);
    }
    $pdo->prepare('DELETE FROM users WHERE id = ?')->execute([$user_id]);
    json_response(200, ['ok' => true]);

default:
    json_response(400, ['ok' => false, 'error' => 'unknown_action']);
}
