<?php
// Entry point for / — kicks the user into the dashboard, which in
// turn bounces unauthenticated visitors to the login page.
declare(strict_types=1);
header('Location: /dashboard/');
exit;
